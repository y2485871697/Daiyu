/* App-private workspace metadata recovery. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef DAIYU_WORKSPACE_REPAIR_H
#define DAIYU_WORKSPACE_REPAIR_H
#include <dirent.h>
#include <sys/xattr.h>
#ifndef WS_LABEL_ATTRIBUTE
#define WS_LABEL_ATTRIBUTE "security.selinux"
#endif
#define WS_MAX_ENTRIES 100000u
struct ws_recovery {
    uid_t uid;
    gid_t gid;
    dev_t device;
    uint64_t mount;
    char label[256];
    size_t label_size;
    int repair;
    int64_t deadline;
    unsigned entries;
};
static void ws_alarm(int number) { (void)number; _exit(74); }
static int ws_active(struct ws_recovery *state) {
    if(interrupted || millis() >= state->deadline || ++state->entries > WS_MAX_ENTRIES) {
        errno=ECANCELED; return -1;
    }
    return 0;
}
static uint64_t ws_mount(int fd) {
    char path[80],line[256]; unsigned long long id=0;
    snprintf(path,sizeof(path),"/proc/self/fdinfo/%d",fd);
    FILE *file=fopen(path,"re");
    if(!file) return 0;
    while(fgets(line,sizeof(line),file)) if(sscanf(line,"mnt_id: %llu",&id)==1) break;
    int saved=errno;
    if(fclose(file)) return 0;
    errno=saved; return (uint64_t)id;
}
static int ws_same(const struct stat *left,const struct stat *right) {
    return left->st_dev==right->st_dev && left->st_ino==right->st_ino &&
        (left->st_mode&S_IFMT)==(right->st_mode&S_IFMT);
}
static int ws_metadata(int fd,struct ws_recovery *state) {
    struct stat node;
    char label[256];
    if(ws_active(state)<0 || fstat(fd,&node)<0) return -1;
    if(node.st_dev!=state->device || ws_mount(fd)!=state->mount ||
        (!S_ISDIR(node.st_mode) && !S_ISREG(node.st_mode)) ||
        (node.st_uid!=0 && node.st_uid!=state->uid)) { errno=EPERM; return -1; }
    char pinned[64]; snprintf(pinned,sizeof(pinned),"/proc/self/fd/%d",fd);
    ssize_t count=getxattr(pinned,WS_LABEL_ATTRIBUTE,label,sizeof(label));
    if(count<0) return -1;
    int label_matches=(size_t)count==state->label_size && !memcmp(label,state->label,(size_t)count);
    static const char unscoped[]="u:object_r:app_data_file:s0";
    int missing_scope=(count==(ssize_t)sizeof(unscoped) || count==(ssize_t)sizeof(unscoped)-1) &&
        !memcmp(label,unscoped,sizeof(unscoped)-1) &&
        (count==(ssize_t)sizeof(unscoped)-1 || label[sizeof(unscoped)-1]==0);
    if(!label_matches && !missing_scope) { errno=EPERM; return -1; }
    int change=node.st_uid!=state->uid || node.st_gid!=state->gid || !label_matches;
    if(!change) return 0;
    if(!state->repair) { errno=EACCES; return -1; }
    /* A hard-linked inode may also have names outside the approved workspace. */
    if(S_ISREG(node.st_mode) && node.st_nlink!=1) { errno=EMLINK; return -1; }
    if(node.st_mode&(S_ISUID|S_ISGID)) { errno=EPERM; return -1; }
    if(ws_active(state)<0) return -1;
    if((node.st_uid!=state->uid || node.st_gid!=state->gid) && fchownat(fd,"",state->uid,state->gid,AT_EMPTY_PATH)<0) return -1;
    if(!label_matches && setxattr(pinned,WS_LABEL_ATTRIBUTE,state->label,state->label_size,0)<0) return -1;
    /* Do not chmod: preserve deliberate modes, executable bits and stale-entry checks. */
    struct stat after;
    if(fstat(fd,&after)<0 || !ws_same(&node,&after) || after.st_uid!=state->uid || after.st_gid!=state->gid) {
        errno=ESTALE; return -1;
    }
    count=getxattr(pinned,WS_LABEL_ATTRIBUTE,label,sizeof(label));
    if(count!=(ssize_t)state->label_size || memcmp(label,state->label,state->label_size)) {
        errno=EACCES; return -1;
    }
    return 0;
}
static int ws_open(int parent,const char *name,struct ws_recovery *state,struct stat *before) {
    if(fstatat(parent,name,before,AT_SYMLINK_NOFOLLOW)<0) return -1;
    if(!S_ISDIR(before->st_mode) && !S_ISREG(before->st_mode)) { errno=EPERM; return -1; }
    int fd=openat(parent,name,O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK|
        (S_ISDIR(before->st_mode)?O_RDONLY|O_DIRECTORY:O_PATH));
    if(fd<0) return -1;
    struct stat actual;
    if(fstat(fd,&actual)<0 || !ws_same(before,&actual) || actual.st_dev!=state->device || ws_mount(fd)!=state->mount) {
        int saved=errno?errno:ESTALE; close(fd); errno=saved; return -1;
    }
    return fd;
}
static int ws_tree(int fd,struct ws_recovery *state,unsigned depth) {
    if(depth>128) { errno=ELOOP; return -1; }
    if(ws_metadata(fd,state)<0) return -1;
    struct stat node;
    if(fstat(fd,&node)<0) return -1;
    if(!S_ISDIR(node.st_mode)) return 0;
    int scan=dup(fd);
    if(scan<0) return -1;
    DIR *directory=fdopendir(scan);
    if(!directory) { int saved=errno; close(scan); errno=saved; return -1; }
    int result=0;
    for(;;) {
        errno=0; struct dirent *entry=readdir(directory);
        if(!entry) { if(errno) result=-1; break; }
        if(!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
        if(ws_active(state)<0) { result=-1; break; }
        struct stat before;
        if(fstatat(fd,entry->d_name,&before,AT_SYMLINK_NOFOLLOW)<0) { result=-1; break; }
        /* Links and special nodes need only their parent for deletion; never open them. */
        if(!S_ISDIR(before.st_mode) && !S_ISREG(before.st_mode)) continue;
        int child=ws_open(fd,entry->d_name,state,&before);
        if(child<0) { result=-1; break; }
        result=ws_tree(child,state,depth+1);
        int saved=errno;
        struct stat current;
        if(result==0 && (fstatat(fd,entry->d_name,&current,AT_SYMLINK_NOFOLLOW)<0 || !ws_same(&before,&current))) {
            result=-1; saved=ESTALE;
        }
        close(child); errno=saved;
        if(result<0) break;
    }
    int saved=errno;
    closedir(directory); errno=saved; return result;
}
/* Ancestors require search, not directory-list permission (Android /data). */
static int ws_open_storage(const char *path) {
    if(!path || path[0]!='/' || strlen(path)>4096) { errno=EINVAL; return -1; }
    int fd=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    const char *part=path+1;
    while(*part) {
        const char *end=strchr(part,'/');
        size_t size=end ? (size_t)(end-part) : strlen(part);
        if(!size || size>255 || (size==1 && part[0]=='.') ||
            (size==2 && !strncmp(part,"..",2)) || (end && !end[1])) {
            close(fd); errno=EINVAL; return -1;
        }
        char name[256]; memcpy(name,part,size); name[size]=0;
        int next=openat(fd,name,(end?O_PATH:O_RDONLY)|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
        int saved=errno; close(fd); fd=next; errno=saved;
        if(fd<0) return -1;
        if(!end) return fd;
        part=end+1;
    }
    close(fd); errno=EINVAL; return -1;
}
static int workspace_repair_main(int argc,char **argv) {
    int repair=argc>1 && !strcmp(argv[1],"--workspace-repair");
    int inspect=argc>1 && !strcmp(argv[1],"--workspace-check");
    if(!repair && !inspect) return -1;
    /* storage, storage dev/ino, relative target, target dev/ino (or 0), tree, deadline */
    if(argc!=11 || (repair && (getuid()!=0 || geteuid()!=0))) return 64;
    uint64_t device,inode,target_device,target_inode,deadline;
    if(root_identity_number(argv[3],&device)<0 || root_identity_number(argv[4],&inode)<0 ||
        root_identity_number(argv[6],&target_device)<0 || root_identity_number(argv[7],&target_inode)<0 ||
        root_identity_number(argv[10],&deadline)<0 || deadline>INT64_MAX ||
        (strcmp(argv[8],"0") && strcmp(argv[8],"1")) || strcmp(argv[9],"DWSP1")) return 64;
    if(strlen(argv[5])>4096 || argv[5][0]=='/' || strchr(argv[5],'\\')) return 64;
    struct sigaction action; memset(&action,0,sizeof(action));
    action.sa_handler=on_signal; sigemptyset(&action.sa_mask);
    if(sigaction(SIGTERM,&action,NULL)<0 || sigaction(SIGINT,&action,NULL)<0) return 74;
    int64_t left=(int64_t)deadline-millis();
    if(left<=0 || left>10000) return 74;
    action.sa_handler=ws_alarm;
    if(sigaction(SIGALRM,&action,NULL)<0) return 74;
    alarm((unsigned)((left+999)/1000));
    struct ws_recovery state={.repair=repair,.deadline=(int64_t)deadline};
    int storage=ws_open_storage(argv[2]),held=-1,result=74;
    int ancestors[2048],ancestor_count=0;
    if(storage<0) goto done;
    struct stat anchor;
    if(fstat(storage,&anchor)<0 || (uint64_t)anchor.st_dev!=device || (uint64_t)anchor.st_ino!=inode ||
        anchor.st_uid<10000 || anchor.st_uid%100000<10000 ||
        (geteuid()!=0 && geteuid()!=anchor.st_uid)) goto done;
    state.uid=anchor.st_uid; state.gid=anchor.st_gid; state.device=anchor.st_dev;
    state.mount=ws_mount(storage);
    ssize_t label_size=fgetxattr(storage,WS_LABEL_ATTRIBUTE,state.label,sizeof(state.label));
    static const char prefix[]="u:object_r:app_data_file:s0:c";
    if(!state.mount || label_size<(ssize_t)sizeof(prefix) || label_size>=(ssize_t)sizeof(state.label) ||
        memcmp(state.label,prefix,sizeof(prefix)-1)) goto done;
    state.label_size=(size_t)label_size;
    struct stat before;
    held=ws_open(storage,"workspace",&state,&before);
    if(held<0) { if(!repair && errno==EACCES) result=77; goto done; }
    char relative[4097]; memcpy(relative,argv[5],strlen(argv[5])+1);
    char *part=relative;
    while(*part) {
        char *slash=strchr(part,'/');
        if(slash) *slash=0;
        if(!*part || !strcmp(part,".") || !strcmp(part,"..") || strlen(part)>255 || (slash && !slash[1])) { result=64; goto done; }
        if(!repair && ws_metadata(held,&state)<0) { if(errno==EACCES) result=77; goto done; }
        if(ancestor_count>=2048) goto done;
        int next=ws_open(held,part,&state,&before);
        if(next<0) { if(!repair && errno==EACCES) result=77; goto done; }
        if(repair) ancestors[ancestor_count++]=held;
        else close(held);
        held=next;
        if(!slash) break;
        part=slash+1;
    }
    struct stat target;
    if(fstat(held,&target)<0 || (target_inode && ((uint64_t)target.st_ino!=target_inode || (uint64_t)target.st_dev!=target_device))) goto done;
    /* Reject a stale target before changing any necessary ancestor metadata. */
    for(int i=0;i<ancestor_count;i++) if(ws_metadata(ancestors[i],&state)<0) goto done;
    int checked=!strcmp(argv[8],"1") ? ws_tree(held,&state,0) : ws_metadata(held,&state);
    if(checked<0) { if(!repair && errno==EACCES) result=77; goto done; }
    if(ws_active(&state)<0) goto done;
    result=printf("DWSP1 OK\n")<0?74:0;
done:
    for(int i=0;i<ancestor_count;i++) close(ancestors[i]);
    if(held>=0) close(held);
    if(storage>=0) close(storage);
    return result;
}
#endif
