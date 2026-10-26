/* Independent host-only rootfs backup protocol. SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef DAIYU_ROOTFS_BACKUP_H
#define DAIYU_ROOTFS_BACKUP_H
#include <dirent.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/xattr.h>

#define RB_LIMIT (32ULL*1024*1024*1024)
#define RB_ENTRIES 500000u
#define RB_MEMORY (64u*1024*1024)
#define RB_LINKS 1048576u
struct rb_attr { char *name; unsigned char *value; uint32_t size; };
struct rb_meta { uint32_t mode,uid,gid,nsec,count; int64_t sec; struct rb_attr *attrs; };
struct rb_link { uint64_t dev,ino; char *path; };
struct rb_dir { char *path; struct rb_meta meta; struct rb_dir *next; };
static uint64_t rb_bytes, rb_mount;
static unsigned rb_entries;
static size_t rb_memory, rb_restore_reserved;
static uint32_t rb_source_uid,rb_source_gid,rb_target_uid,rb_target_gid;
static struct rb_link *rb_links;
static struct rb_dir *rb_dirs;
static int rb_rootfd;
static void rb_fail(const char *reason) { fprintf(stderr,"rootfs backup: %s: %s\n",reason,strerror(errno)); exit(74); }
static void rb_need(int ok,const char *why) { if(!ok) { errno=EINVAL; rb_fail(why); } }
static void *rb_alloc(size_t size) {
    rb_need(size<=RB_MEMORY && rb_restore_reserved<=RB_MEMORY-size && rb_memory<=RB_MEMORY-size-rb_restore_reserved,"memory limit");
    void *p=calloc(1,size?size:1); if(!p) rb_fail("allocate"); rb_memory+=size; return p;
}
static char *rb_dup(const char *value) { size_t n=strlen(value)+1; char *p=rb_alloc(n); memcpy(p,value,n); return p; }
static void rb_free(void *p,size_t n) { if(p) { free(p); rb_memory-=n; } }
static void rb_io(int fd,void *buffer,size_t size,int writing) {
    rb_need(size<=RB_LIMIT-rb_bytes,"size limit"); rb_bytes+=size;
    unsigned char *p=buffer;
    while(size) { ssize_t n=writing?write(fd,p,size):read(fd,p,size); if(n<0 && errno==EINTR) continue;
        if(n<=0) rb_fail(writing?"write":"truncated stream");
        p+=n; size-=(size_t)n; }
}
static void rb_u32(uint32_t *v,int writing) {
    unsigned char p[4]; if(writing) { p[0]=*v>>24;p[1]=*v>>16;p[2]=*v>>8;p[3]=*v; }
    rb_io(writing?1:0,p,4,writing); if(!writing) *v=((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static void rb_u64(uint64_t *v,int writing) {
    uint32_t hi=(uint32_t)(*v>>32),lo=(uint32_t)*v; rb_u32(&hi,writing);rb_u32(&lo,writing);
    if(!writing) *v=((uint64_t)hi<<32)|lo;
}
static void rb_string(char *text,size_t capacity,int writing,int leaf) {
    uint32_t n=writing?(uint32_t)strlen(text):0;rb_u32(&n,writing);
    rb_need(n>0 && n<capacity,"string limit");rb_io(writing?1:0,text,n,writing);
    if(!writing) { rb_need(memchr(text,0,n)==NULL,"embedded nul");text[n]=0; }
    if(leaf) rb_need(strcmp(text,".") && strcmp(text,"..") && !strchr(text,'/'),"unsafe name");
}
static uint64_t rb_mnt(int fd) {
    char path[80],line[256];unsigned long long id=0;snprintf(path,sizeof(path),"/proc/self/fdinfo/%d",fd);
    FILE *file=fopen(path,"re");if(!file)rb_fail("fdinfo");
    while(fgets(line,sizeof(line),file)) if(sscanf(line,"mnt_id: %llu",&id)==1)break;
    if(fclose(file))rb_fail("fdinfo close");
    rb_need(id!=0,"mount identity missing");return id;
}
static void rb_same_mount(int fd) { rb_need(rb_mnt(fd)==rb_mount,"mounted subtree"); }
static void rb_procpath(char *path,size_t size,int fd,const char *name) {
    int n=snprintf(path,size,"/proc/self/fd/%d/%s",fd,name);rb_need(n>0 && (size_t)n<size,"proc path");
}
static void rb_read_meta(struct rb_meta *m,int fd,int parent,const char *name,const struct stat *st) {
    m->mode=(uint32_t)st->st_mode&07777;m->uid=st->st_uid;m->gid=st->st_gid;
    m->sec=st->st_mtim.tv_sec;m->nsec=(uint32_t)st->st_mtim.tv_nsec;
    char path[PATH_MAX]; if(fd<0)rb_procpath(path,sizeof(path),parent,name);
    ssize_t n=fd>=0?flistxattr(fd,NULL,0):llistxattr(path,NULL,0);
    if(n<0 && errno==ENOTSUP)n=0;
    if(n<0)rb_fail("list attributes");
    rb_need(n<=65536,"attribute names");
    char *names=rb_alloc((size_t)n+1);
    if(n && (fd>=0?flistxattr(fd,names,(size_t)n):llistxattr(path,names,(size_t)n))!=n)rb_fail("attributes changed");
    for(ssize_t p=0;p<n;) { size_t len=strnlen(names+p,(size_t)(n-p));rb_need(len>0 && len<(size_t)(n-p),"attribute name");
        if(strcmp(names+p,"security.selinux"))m->count++;
        p+=(ssize_t)len+1; }
    rb_need(m->count<=1024,"attribute count");m->attrs=rb_alloc(m->count*sizeof(*m->attrs));
    uint32_t index=0;size_t total=0;
    for(ssize_t p=0;p<n;) { const char *key=names+p;size_t len=strlen(key);p+=(ssize_t)len+1;if(!strcmp(key,"security.selinux"))continue;
        struct rb_attr *a=&m->attrs[index++];a->name=rb_dup(key);
        ssize_t bytes=fd>=0?fgetxattr(fd,key,NULL,0):lgetxattr(path,key,NULL,0);if(bytes<0)rb_fail("read attribute");
        rb_need(bytes<=1048576 && total<=1048576-(size_t)bytes,"attribute bytes");total+=(size_t)bytes;
        a->size=(uint32_t)bytes;a->value=rb_alloc((size_t)bytes);
        if((fd>=0?fgetxattr(fd,key,a->value,(size_t)bytes):lgetxattr(path,key,a->value,(size_t)bytes))!=bytes)rb_fail("attribute changed");
    }
    rb_free(names,(size_t)n+1);
}
static void rb_meta_io(struct rb_meta *m,int writing) {
    rb_u32(&m->mode,writing);rb_u32(&m->uid,writing);rb_u32(&m->gid,writing);
    uint64_t sec=(uint64_t)m->sec;rb_u64(&sec,writing);m->sec=(int64_t)sec;rb_u32(&m->nsec,writing);rb_u32(&m->count,writing);
    rb_need(m->mode<=07777 && m->nsec<1000000000 && m->count<=1024,"metadata limit");
    if(!writing)m->attrs=rb_alloc(m->count*sizeof(*m->attrs));
    size_t total=0;
    for(uint32_t i=0;i<m->count;i++) {
        struct rb_attr *a=&m->attrs[i];char name[256];if(writing){rb_need(strlen(a->name)<sizeof(name),"attribute name");strcpy(name,a->name);}
        rb_string(name,sizeof(name),writing,0);rb_need(strcmp(name,"security.selinux") && !strchr(name,'/'),"host attribute");
        if(!writing){for(uint32_t j=0;j<i;j++)rb_need(strcmp(name,m->attrs[j].name),"duplicate attribute");a->name=rb_dup(name);}
        rb_u32(&a->size,writing);rb_need(a->size<=1048576 && total<=1048576-a->size,"attribute limit");total+=a->size;
        if(!writing)a->value=rb_alloc(a->size);
        rb_io(writing?1:0,a->value,a->size,writing);
    }
}
static void rb_meta_free(struct rb_meta *m) {
    for(uint32_t i=0;i<m->count;i++){struct rb_attr *a=&m->attrs[i];rb_free(a->name,strlen(a->name)+1);rb_free(a->value,a->size);}
    rb_free(m->attrs,m->count*sizeof(*m->attrs));memset(m,0,sizeof(*m));
}
static void rb_apply(struct rb_meta *m,int fd,int parent,const char *name,int symlink) {
    uid_t uid=m->uid==rb_source_uid?rb_target_uid:m->uid;gid_t gid=m->gid==rb_source_gid?rb_target_gid:m->gid;
    if((symlink?fchownat(parent,name,uid,gid,AT_SYMLINK_NOFOLLOW):fchown(fd,uid,gid))<0)rb_fail("restore ownership");
    if(symlink)rb_need(m->mode==0777,"symlink permissions");else if(fchmod(fd,m->mode)<0)rb_fail("restore permissions");
    char path[PATH_MAX];if(symlink)rb_procpath(path,sizeof(path),parent,name);
    /* Remove inherited ACLs/attributes before installing the captured set. SELinux remains host-owned. */
    ssize_t n=symlink?llistxattr(path,NULL,0):flistxattr(fd,NULL,0);if(n<0 && errno==ENOTSUP)n=0;
    if(n<0)rb_fail("list destination attributes");
    rb_need(n<=65536,"destination attributes");char *names=rb_alloc((size_t)n+1);
    if(n && (symlink?llistxattr(path,names,(size_t)n):flistxattr(fd,names,(size_t)n))!=n)rb_fail("destination attributes changed");
    for(ssize_t p=0;p<n;){size_t len=strnlen(names+p,(size_t)(n-p));rb_need(len>0 && len<(size_t)(n-p),"destination attribute name");
        if(strcmp(names+p,"security.selinux") && (symlink?lremovexattr(path,names+p):fremovexattr(fd,names+p))<0)rb_fail("remove inherited attribute");
        p+=(ssize_t)len+1;}
    rb_free(names,(size_t)n+1);
    for(uint32_t i=0;i<m->count;i++){struct rb_attr *a=&m->attrs[i];if((symlink?lsetxattr(path,a->name,a->value,a->size,0):fsetxattr(fd,a->name,a->value,a->size,0))<0)rb_fail("restore attribute");}
    struct timespec times[2]={{.tv_nsec=UTIME_OMIT},{.tv_sec=(time_t)m->sec,.tv_nsec=m->nsec}};
    if((symlink?utimensat(parent,name,times,AT_SYMLINK_NOFOLLOW):futimens(fd,times))<0)rb_fail("restore timestamp");
    if(!symlink && fsync(fd)<0)rb_fail("sync restored entry");
}
static int rb_changed(const struct stat *a,const struct stat *b) {
    return a->st_dev!=b->st_dev || a->st_ino!=b->st_ino || a->st_size!=b->st_size || a->st_mode!=b->st_mode ||
        a->st_uid!=b->st_uid || a->st_gid!=b->st_gid || a->st_mtim.tv_sec!=b->st_mtim.tv_sec || a->st_mtim.tv_nsec!=b->st_mtim.tv_nsec ||
        a->st_ctim.tv_sec!=b->st_ctim.tv_sec || a->st_ctim.tv_nsec!=b->st_ctim.tv_nsec;
}
static struct rb_link *rb_find_link(uint64_t dev,uint64_t ino) {
    uint64_t slot=(ino^(dev*11400714819323198485ULL))&(RB_LINKS-1);
    while(rb_links[slot].path && (rb_links[slot].dev!=dev || rb_links[slot].ino!=ino))slot=(slot+1)&(RB_LINKS-1);
    return &rb_links[slot];
}
static void rb_join(char *result,size_t cap,const char *base,const char *name) {
    int n=snprintf(result,cap,"%s%s%s",base,*base?"/":"",name);rb_need(n>0 && (size_t)n<cap,"path limit");
}
static int rb_path_parent(const char *path,char *leaf) {
    rb_need(*path && *path!='/' && strlen(path)<PATH_MAX,"relative path");char copy[PATH_MAX];strcpy(copy,path);
    int fd=dup(rb_rootfd);if(fd<0)rb_fail("duplicate root");char *p=copy;
    while(1){char *slash=strchr(p,'/');if(!slash)break;*slash=0;rb_need(*p && strcmp(p,".") && strcmp(p,".."),"unsafe parent");
        int next=openat(fd,p,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(next<0)rb_fail("open parent");rb_same_mount(next);close(fd);fd=next;p=slash+1;}
    rb_need(*p && strlen(p)<=NAME_MAX && strcmp(p,".") && strcmp(p,".."),"unsafe leaf");strcpy(leaf,p);return fd;
}
static void rb_pack_dir(int fd,const char *relative,unsigned depth) {
    rb_need(depth<=128,"depth limit");rb_same_mount(fd);struct stat before,after;if(fstat(fd,&before)<0)rb_fail("stat directory");
    struct rb_meta meta={0};rb_read_meta(&meta,fd,0,NULL,&before);
    size_t restore_cost=sizeof(struct rb_dir)+strlen(relative)+1+meta.count*sizeof(struct rb_attr);
    for(uint32_t i=0;i<meta.count;i++)restore_cost+=strlen(meta.attrs[i].name)+1+meta.attrs[i].size;
    rb_need(restore_cost<=RB_MEMORY && rb_restore_reserved<=RB_MEMORY-restore_cost && rb_memory<=RB_MEMORY-restore_cost-rb_restore_reserved,"restore metadata limit");
    rb_restore_reserved+=restore_cost;rb_meta_io(&meta,1);rb_meta_free(&meta);
    DIR *directory=fdopendir(dup(fd));if(!directory)rb_fail("open directory");struct dirent *entry;
    errno=0;
    while((entry=readdir(directory))!=NULL){const char *name=entry->d_name;if(!strcmp(name,".")||!strcmp(name,".."))continue;
        if(depth==0 && !strcmp(name,".daiyu-rootfs-install"))continue;
        rb_need(++rb_entries<=RB_ENTRIES,"entry limit");struct stat st;if(fstatat(fd,name,&st,AT_SYMLINK_NOFOLLOW)<0)rb_fail("stat entry");
        unsigned char type=S_ISDIR(st.st_mode)?'D':S_ISREG(st.st_mode)?'F':S_ISLNK(st.st_mode)?'L':0;
        rb_need(type!=0,"unsupported special file");char path[PATH_MAX];rb_join(path,sizeof(path),relative,name);
        struct rb_link *link=NULL;if(type=='F' && st.st_nlink>1){link=rb_find_link(st.st_dev,st.st_ino);if(link->path)type='H';}
        rb_io(1,&type,1,1);char leaf[NAME_MAX+1];strcpy(leaf,name);rb_string(leaf,sizeof(leaf),1,1);
        if(type=='D'){int child=openat(fd,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(child<0)rb_fail("open directory");
            struct stat actual;if(fstat(child,&actual)<0 || actual.st_dev!=st.st_dev || actual.st_ino!=st.st_ino)rb_fail("directory changed");rb_pack_dir(child,path,depth+1);close(child);}
        else if(type=='H'){char target[PATH_MAX];strcpy(target,link->path);rb_string(target,sizeof(target),1,0);}
        else if(type=='F') {int node=openat(fd,name,O_PATH|O_NOFOLLOW|O_CLOEXEC);if(node<0)rb_fail("open file identity");rb_same_mount(node);
            struct stat actual;if(fstat(node,&actual)<0 || rb_changed(&st,&actual))rb_fail("file changed");
            char handle[80];snprintf(handle,sizeof(handle),"/proc/self/fd/%d",node);int file=open(handle,O_RDONLY|O_NONBLOCK|O_CLOEXEC);if(file<0)rb_fail("open file");close(node);
            struct rb_meta m={0};rb_read_meta(&m,file,0,NULL,&st);rb_meta_io(&m,1);rb_meta_free(&m);rb_need(st.st_size>=0,"file size");uint64_t bytes=(uint64_t)st.st_size;rb_u64(&bytes,1);
            unsigned char block[65536];uint64_t left=bytes;while(left){size_t count=left>sizeof(block)?sizeof(block):(size_t)left;ssize_t got=read(file,block,count);if(got<0 && errno==EINTR)continue;if(got<=0)rb_fail("file truncated");rb_io(1,block,(size_t)got,1);left-=(size_t)got;}
            if(fstat(file,&actual)<0 || rb_changed(&st,&actual))rb_fail("file changed");
            close(file);
            if(link){link->dev=st.st_dev;link->ino=st.st_ino;link->path=rb_dup(path);}}
        else {int node=openat(fd,name,O_PATH|O_NOFOLLOW|O_CLOEXEC);if(node<0)rb_fail("open symlink");rb_same_mount(node);close(node);
            struct rb_meta m={0};rb_read_meta(&m,-1,fd,name,&st);rb_meta_io(&m,1);rb_meta_free(&m);
            char target[PATH_MAX];ssize_t n=readlinkat(fd,name,target,sizeof(target)-1);if(n<0)rb_fail("read link");rb_need(n>0 && (size_t)n<sizeof(target)-1,"link limit");target[n]=0;rb_string(target,sizeof(target),1,0);
            if(fstatat(fd,name,&after,AT_SYMLINK_NOFOLLOW)<0 || rb_changed(&st,&after))rb_fail("symlink changed");}
        errno=0;
    }
    if(errno)rb_fail("read directory");
    if(closedir(directory)<0)rb_fail("close directory");
    if(fstat(fd,&after)<0 || rb_changed(&before,&after))rb_fail("directory changed");
    unsigned char end='E';rb_io(1,&end,1,1);
}
static void rb_unpack_dir(int fd,const char *relative,unsigned depth) {
    rb_need(depth<=128,"depth limit");struct rb_dir *pending=rb_alloc(sizeof(*pending));pending->path=rb_dup(relative);
    rb_meta_io(&pending->meta,0);pending->next=rb_dirs;rb_dirs=pending;
    while(1){unsigned char type;rb_io(0,&type,1,0);if(type=='E')break;rb_need(++rb_entries<=RB_ENTRIES,"entry limit");
        rb_need(type=='D'||type=='F'||type=='L'||type=='H',"record type");char name[NAME_MAX+1];rb_string(name,sizeof(name),0,1);
        rb_need(depth!=0 || strcmp(name,".daiyu-rootfs-install"),"old installation marker");char path[PATH_MAX];rb_join(path,sizeof(path),relative,name);
        if(type=='D'){if(mkdirat(fd,name,0700)<0)rb_fail("create directory");int child=openat(fd,name,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(child<0)rb_fail("open new directory");rb_same_mount(child);rb_unpack_dir(child,path,depth+1);close(child);}
        else if(type=='H'){char target[PATH_MAX],leaf[NAME_MAX+1];rb_string(target,sizeof(target),0,0);int parent=rb_path_parent(target,leaf);struct stat st;
            if(fstatat(parent,leaf,&st,AT_SYMLINK_NOFOLLOW)<0 || !S_ISREG(st.st_mode))rb_fail("hardlink target");
            /* Targets must have been emitted as full regular records, not an imported hardlink chain. */
            struct rb_link *record=rb_find_link(st.st_dev,st.st_ino);
            if(!record->path){rb_need(st.st_nlink==1,"hardlink chain");record->dev=st.st_dev;record->ino=st.st_ino;record->path=rb_dup(target);}
            else rb_need(!strcmp(record->path,target),"hardlink chain");
            if(linkat(parent,leaf,fd,name,0)<0)rb_fail("restore hardlink");
            close(parent);}
        else {struct rb_meta m={0};rb_meta_io(&m,0);
            if(type=='F'){uint64_t bytes=0;rb_u64(&bytes,0);rb_need(bytes<=RB_LIMIT-rb_bytes,"file limit");
                int file=openat(fd,name,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(file<0)rb_fail("create file");rb_same_mount(file);
                unsigned char block[65536];while(bytes){size_t count=bytes>sizeof(block)?sizeof(block):(size_t)bytes;rb_io(0,block,count,0);size_t offset=0;
                    while(offset<count){ssize_t n=write(file,block+offset,count-offset);if(n<0 && errno==EINTR)continue;if(n<=0)rb_fail("restore file");offset+=(size_t)n;}bytes-=count;}
                rb_apply(&m,file,0,NULL,0);if(close(file)<0)rb_fail("close file");}
            else {char target[PATH_MAX];rb_string(target,sizeof(target),0,0);if(symlinkat(target,fd,name)<0)rb_fail("restore symlink");rb_apply(&m,-1,fd,name,1);}
            rb_meta_free(&m);}
    }
    if(fsync(fd)<0)rb_fail("sync directory");
}
static int rb_cleanup_child(int parent,const char *name) {
    int pathfd=openat(parent,name,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(pathfd<0)rb_fail("cleanup handle");rb_same_mount(pathfd);
    char path[80];snprintf(path,sizeof(path),"/proc/self/fd/%d",pathfd);
    if(chmod(path,0700)<0)rb_fail("cleanup chmod");
    int fd=open(path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(fd<0)rb_fail("cleanup open");close(pathfd);return fd;
}
static int rb_open_root(const char *path) {
    rb_need(path[0]=='/' && strlen(path)<PATH_MAX,"root path");char copy[PATH_MAX];strcpy(copy,path+1);
    int fd=open("/",O_PATH|O_DIRECTORY|O_CLOEXEC);if(fd<0)rb_fail("open filesystem root");
    char *save=NULL,*part=strtok_r(copy,"/",&save);rb_need(part!=NULL,"root directory");
    while(part){rb_need(strcmp(part,".") && strcmp(part,".."),"root path component");int next=openat(fd,part,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(next<0)rb_fail("open root component");
        close(fd);fd=next;part=strtok_r(NULL,"/",&save);}
    return fd;
}
static void rb_remove_dir(int fd,unsigned depth) {
    rb_need(depth<=128,"cleanup depth");rb_same_mount(fd);if(fchmod(fd,0700)<0)rb_fail("cleanup permissions");DIR *dir=fdopendir(dup(fd));if(!dir)rb_fail("cleanup directory");struct dirent *entry;errno=0;
    while((entry=readdir(dir))!=NULL){if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,".."))continue;
        struct stat st;if(fstatat(fd,entry->d_name,&st,AT_SYMLINK_NOFOLLOW)<0)rb_fail("cleanup stat");
        if(S_ISDIR(st.st_mode)){int child=rb_cleanup_child(fd,entry->d_name);rb_remove_dir(child,depth+1);close(child);if(unlinkat(fd,entry->d_name,AT_REMOVEDIR)<0)rb_fail("cleanup rmdir");}
        else if(unlinkat(fd,entry->d_name,0)<0)rb_fail("cleanup unlink");
        errno=0;}
    if(errno)rb_fail("cleanup readdir");
    closedir(dir);if(fsync(fd)<0)rb_fail("cleanup sync");
}
static uint64_t rb_number(const char *text) { char *end;errno=0;unsigned long long n=strtoull(text,&end,10);rb_need(*text && *text!='-' && !*end && !errno,"numeric argument");return n; }
static int rootfs_backup_main(int argc,char **argv) {
    int pack=argc>=2 && !strcmp(argv[1],"--rootfs-pack");int unpack=argc>=2 && !strcmp(argv[1],"--rootfs-unpack");int remove=argc>=2 && !strcmp(argv[1],"--rootfs-remove");
    if(!pack && !unpack && !remove)return -1;
    rb_need(argc==(unpack?9:5),"arguments");rb_need(argv[2][0]=='/' && strcmp(argv[2],"/"),"root directory");
    rb_rootfd=rb_open_root(argv[2]);if(rb_rootfd<0)rb_fail("open root");
    struct stat root;if(fstat(rb_rootfd,&root)<0)rb_fail("stat root");rb_need((uint64_t)root.st_dev==rb_number(argv[3]) && (uint64_t)root.st_ino==rb_number(argv[4]),"root identity changed");rb_mount=rb_mnt(rb_rootfd);
    char root_path[80];snprintf(root_path,sizeof(root_path),"/proc/self/fd/%d",rb_rootfd);
    if(remove && chmod(root_path,0700)<0)rb_fail("cleanup root permissions");
    int readable_root=open(root_path,O_RDONLY|O_DIRECTORY|O_CLOEXEC);if(readable_root<0)rb_fail("open readable root");close(rb_rootfd);rb_rootfd=readable_root;
    if(remove){rb_remove_dir(rb_rootfd,0);if(write(1,"DRFS1 OK\n",9)!=9)rb_fail("cleanup receipt");return 0;}
    rb_links=calloc(RB_LINKS,sizeof(*rb_links));if(!rb_links)rb_fail("link index");
    char magic[6]="DRFS1";rb_io(pack?1:0,magic,5,pack);rb_need(!memcmp(magic,"DRFS1",5),"protocol");
    if(pack)rb_pack_dir(rb_rootfd,"",0);
    else {
        uint64_t ids[4];for(int i=0;i<4;i++){ids[i]=rb_number(argv[i+5]);rb_need(ids[i]<UINT32_MAX,"uid range");}
        rb_source_uid=(uint32_t)ids[0];rb_source_gid=(uint32_t)ids[1];rb_target_uid=(uint32_t)ids[2];rb_target_gid=(uint32_t)ids[3];
        rb_need(geteuid()==0 || (rb_target_uid==getuid() && rb_target_gid==getgid()),"target identity");
        rb_need((root.st_mode&0777)==0700,"private staging required");DIR *d=fdopendir(dup(rb_rootfd));if(!d)rb_fail("staging directory");struct dirent *e;errno=0;
        while((e=readdir(d))!=NULL)rb_need(!strcmp(e->d_name,".")||!strcmp(e->d_name,".."),"staging not empty");
        if(errno)rb_fail("staging readdir");
        closedir(d);
        rb_unpack_dir(rb_rootfd,"",0);
    }
    unsigned char end='Z';rb_io(pack?1:0,&end,1,pack);rb_need(end=='Z',"end marker");
    if(!pack){unsigned char extra;ssize_t n;do{n=read(0,&extra,1);}while(n<0 && errno==EINTR);rb_need(n==0,"trailing data");
        for(struct rb_dir *d=rb_dirs;d;d=d->next){int fd;if(!*d->path)fd=dup(rb_rootfd);else{char leaf[NAME_MAX+1];int parent=rb_path_parent(d->path,leaf);fd=openat(parent,leaf,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);close(parent);}if(fd<0)rb_fail("restore directory metadata");rb_apply(&d->meta,fd,0,NULL,0);close(fd);}
        if(write(1,"DRFS1 OK\n",9)!=9)rb_fail("restore receipt");}
    close(rb_rootfd);return 0;
}
#endif
