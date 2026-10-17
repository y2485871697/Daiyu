/* Independent Daiyu root scope. SPDX-License-Identifier: GPL-3.0-or-later */
#include <sched.h>
#include <sys/mount.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#ifndef CGROUP2_SUPER_MAGIC
#define CGROUP2_SUPER_MAGIC 0x63677270
#endif
#ifndef __WALL
#define __WALL 0x40000000
#endif

/* Only explicit root launches use this protocol. X is a leader status, not drain.
 * S pins a fresh scope; D acknowledges kill, empty scope, full reap and removal.
 * F is permitted only before a guest fork with no remaining created scope.
 * Otherwise no D means UNKNOWN, even if su or this monitor has exited. */
static int root_mode;
static struct {
    int parent, dir, procs, killfd, events;
    char name[48];
    struct stat identity;
} root_scope = { .parent=-1, .dir=-1, .procs=-1, .killfd=-1, .events=-1 };

static void root_close_fds(void) {
    int fds[]={root_scope.procs,root_scope.killfd,root_scope.events,root_scope.dir,root_scope.parent};
    for(size_t i=0;i<sizeof(fds)/sizeof(fds[0]);i++) if(fds[i]>=0) close(fds[i]);
    root_scope.parent=root_scope.dir=root_scope.procs=root_scope.killfd=root_scope.events=-1;
}
static int root_write(int fd,const char *text) {
    if(lseek(fd,0,SEEK_SET)<0) return -1;
    ssize_t n; size_t size=strlen(text);
    do { n=write(fd,text,size); } while(n<0 && errno==EINTR);
    if(n==(ssize_t)size) return 0;
    if(n>=0) errno=EIO;
    return -1;
}
static int root_read(int fd,char *bytes,size_t size) {
    ssize_t n;
    do { n=pread(fd,bytes,size-1,0); } while(n<0 && errno==EINTR);
    if(n<=0 || n==(ssize_t)(size-1)) { if(n>=0) errno=EIO; return -1; }
    bytes[n]=0; return 0;
}
static int root_flush(int timeout_ms);
static int root_create(void) {
    unsigned char nonce[16]; size_t at=0; int made=0,have_identity=0,typefd=-1;
    struct statfs fs; char type[64];
    if(getuid()!=0 || geteuid()!=0) { errno=EPERM; goto fail; }
    if(signal(SIGCHLD,SIG_DFL)==SIG_ERR || prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0)<0) goto fail;
    while(at<sizeof(nonce)) {
        ssize_t n=syscall(SYS_getrandom,nonce+at,sizeof(nonce)-at,0);
        if(n<0 && errno==EINTR) continue;
        if(n<=0) { if(!n) errno=EIO; goto fail; }
        at+=(size_t)n;
    }
    memcpy(root_scope.name,"daiyu-",6);
    for(size_t i=0;i<sizeof(nonce);i++) snprintf(root_scope.name+6+2*i,3,"%02x",nonce[i]);
    root_scope.parent=open("/sys/fs/cgroup/uid_0",O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(root_scope.parent<0 || fstatfs(root_scope.parent,&fs)<0) goto fail;
    if((unsigned long)fs.f_type!=CGROUP2_SUPER_MAGIC) { errno=ENODEV; goto fail; }
    /* EEXIST is never adopted. No existing scope control file is written. */
    if(mkdirat(root_scope.parent,root_scope.name,0700)<0) goto fail;
    made=1;
    int stat_result;
    do { stat_result=fstatat(root_scope.parent,root_scope.name,&root_scope.identity,AT_SYMLINK_NOFOLLOW); }
    while(stat_result<0 && errno==EINTR);
    if(stat_result<0) goto fail;
    have_identity=1;
    root_scope.dir=openat(root_scope.parent,root_scope.name,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    struct stat opened;
    if(root_scope.dir<0 || fstat(root_scope.dir,&opened)<0) goto fail;
    if(opened.st_dev!=root_scope.identity.st_dev || opened.st_ino!=root_scope.identity.st_ino) {
        errno=ESTALE; goto fail;
    }
    typefd=openat(root_scope.dir,"cgroup.type",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(typefd<0 || root_read(typefd,type,sizeof(type))<0) goto fail;
    close(typefd); typefd=-1;
    if(strcmp(type,"domain\n")) { errno=EOPNOTSUPP; goto fail; }
    root_scope.procs=openat(root_scope.dir,"cgroup.procs",O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
    root_scope.killfd=openat(root_scope.dir,"cgroup.kill",O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
    root_scope.events=openat(root_scope.dir,"cgroup.events",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(root_scope.procs<0 || root_scope.killfd<0 || root_scope.events<0) goto fail;
    return 0;
fail: {
    int saved=errno;
    if(typefd>=0) close(typefd);
    int removed=!made;
    if(made && have_identity) {
        struct stat current;
        int checked;
        do { checked=fstatat(root_scope.parent,root_scope.name,&current,AT_SYMLINK_NOFOLLOW); }
        while(checked<0 && errno==EINTR);
        if(checked==0 && current.st_dev==root_scope.identity.st_dev && current.st_ino==root_scope.identity.st_ino) {
            int result;
            do { result=unlinkat(root_scope.parent,root_scope.name,AT_REMOVEDIR); } while(result<0 && errno==EINTR);
            removed=result==0;
            if(!removed) saved=errno;
        }
    }
    if(removed) {
        const char *receipt="DROOT0 guest=0 scope_clean=1";
        (void)frame('F',receipt,strlen(receipt)); (void)root_flush(500);
    } else {
        unsigned char bytes[4]; put32(bytes,(uint32_t)saved);
        (void)frame('S',root_scope.name,strlen(root_scope.name));
        (void)frame('E',bytes,4); (void)root_flush(500);
    }
    root_close_fds(); errno=saved; return -1;
} }
static int root_enter(void) {
    int result=root_write(root_scope.procs,"0\n"),saved=errno;
    root_close_fds(); errno=saved;
    if(result<0 || unshare(CLONE_NEWNS)<0 || mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)<0) return -1;
    return 0;
}
static int root_populated(void) {
    char bytes[1024];
    if(root_read(root_scope.events,bytes,sizeof(bytes))<0) return -1;
    char *line=bytes;
    while(line && *line) {
        if(!strncmp(line,"populated 0\n",12)) return 0;
        if(!strncmp(line,"populated 1\n",12)) return 1;
        line=strchr(line,'\n'); if(line) line++;
    }
    errno=EPROTO; return -1;
}
static int root_flush(int timeout_ms) {
    int flags=fcntl(STDOUT_FILENO,F_GETFL);
    if(flags<0 || fcntl(STDOUT_FILENO,F_SETFL,flags|O_NONBLOCK)<0) return -1;
    int64_t deadline=millis()+timeout_ms;
    while(out_len) {
        if(millis()>=deadline) { errno=ETIMEDOUT; return -1; }
        struct pollfd fd={STDOUT_FILENO,POLLOUT,0};
        int result=poll(&fd,1,50);
        if(result<0) { if(errno==EINTR) continue; return -1; }
        if(fd.revents&(POLLERR|POLLHUP|POLLNVAL)) { errno=EPIPE; return -1; }
        if(!(fd.revents&POLLOUT)) continue;
        ssize_t n=write(STDOUT_FILENO,outgoing,out_len);
        if(n>0) { memmove(outgoing,outgoing+n,out_len-(size_t)n); out_len-=(size_t)n; }
        else if(n<0 && errno!=EAGAIN && errno!=EINTR) return -1;
    }
    return 0;
}
static ssize_t root_exec_result(int fd,int *error) {
    int64_t deadline=millis()+3000;
    for(;;) {
        if(interrupted) { errno=ECANCELED; return -1; }
        if(millis()>=deadline) { errno=ETIMEDOUT; return -1; }
        struct pollfd fds[2]={{fd,POLLIN,0},{STDIN_FILENO,POLLIN,0}};
        if(poll(fds,2,50)<0) { if(errno==EINTR) continue; return -1; }
        if(fds[1].revents&(POLLHUP|POLLERR|POLLNVAL)) { errno=ECANCELED; return -1; }
        if(fds[1].revents&POLLIN) {
            if(in_len==sizeof(incoming)) { errno=EPROTO; return -1; }
            ssize_t n=read(STDIN_FILENO,incoming+in_len,sizeof(incoming)-in_len);
            if(!n) { errno=ECANCELED; return -1; }
            if(n<0) { if(errno==EINTR || errno==EAGAIN) continue; return -1; }
            in_len+=(size_t)n;
            if(in_len>=5) { errno=incoming[0]=='C' && be32(incoming+1)==0 ? ECANCELED : EPROTO; return -1; }
        }
        if(fds[0].revents&(POLLIN|POLLHUP|POLLERR)) {
            ssize_t n=read(fd,error,sizeof(*error));
            if(n<0 && errno==EINTR) continue;
            return n;
        }
        if(fds[0].revents&POLLNVAL) { errno=EBADF; return -1; }
    }
}
/* The direct child remains unreaped, so its PID cannot have been recycled. */
static void root_stop(pid_t child) {
    if(child>0) (void)kill(child,SIGKILL);
    (void)root_write(root_scope.killfd,"1\n");
}
static int root_finish(pid_t child,int master,int result) {
    int error=0,drained=0;
    /* Kill the pinned child before inspecting an empty scope: it may not have enrolled yet. */
    if(child>0 && kill(child,SIGKILL)<0 && errno!=ESRCH) error=errno;
    if(root_write(root_scope.killfd,"1\n")<0 && !error) error=errno;
    if(master>=0) close(master);
    int64_t deadline=millis()+3000;
    while(!error) {
        int no_children=0;
        for(int i=0;i<256;i++) {
            pid_t pid=waitpid(-1,NULL,WNOHANG|__WALL);
            if(pid>0) continue;
            if(!pid || errno==EINTR) break;
            if(errno==ECHILD) { no_children=1; break; }
            error=errno; break;
        }
        if(error) break;
        int populated=root_populated();
        if(populated<0) { error=errno; break; }
        if(!populated && no_children) {
            struct stat current;
            if(fstatat(root_scope.parent,root_scope.name,&current,AT_SYMLINK_NOFOLLOW)<0) { error=errno; break; }
            if(current.st_dev!=root_scope.identity.st_dev || current.st_ino!=root_scope.identity.st_ino) { error=ESTALE; break; }
            if(unlinkat(root_scope.parent,root_scope.name,AT_REMOVEDIR)<0) { error=errno; break; }
            drained=1; break;
        }
        if(millis()>=deadline) { error=ETIMEDOUT; break; }
        struct timespec nap={0,20*1000*1000}; (void)nanosleep(&nap,NULL);
    }
    root_close_fds();
    /* Cleanup precedes transport flushing; a blocked output reader cannot prevent kill/reap. */
    if(!drained) {
        unsigned char bytes[4]; put32(bytes,(uint32_t)(error ? error : EIO));
        if(root_flush(500)==0) { (void)frame('E',bytes,4); (void)root_flush(500); }
        return 75;
    }
    if(root_flush(1000)<0) return 75;
    char receipt[160];
    int n=snprintf(receipt,sizeof(receipt),"DROOT1 scope=%s kill=1 populated=0 reaped=1 removed=1",root_scope.name);
    if(n<0 || (size_t)n>=sizeof(receipt) || frame('D',receipt,(size_t)n)<0 || root_flush(1000)<0) return 75;
    return result;
}

static int root_start_failed(pid_t child,int master,int result,int error) {
    unsigned char bytes[4]; put32(bytes,(uint32_t)(error ? error : EIO));
    (void)frame('E',bytes,4);
    return root_finish(child,master,result);
}
