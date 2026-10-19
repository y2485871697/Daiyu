/* Host-only cgroup fault model. Never opens the real cgroup filesystem. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <unistd.h>
static char fake_parent[128];
static int fail_open,fail_stat,fail_unlink,interrupt_unlink,interrupt_stat;
static int preflight,pre_case,pre_ioctl_seen;
static int proc_test,mount_calls,mount_failure;
static int fake_posix_openpt(int flags) {
    if(preflight && pre_case==3) { errno=EMFILE; return -1; }
    return posix_openpt(flags);
}
static int fake_ioctl(int fd,unsigned long request,void *arg) {
    if(preflight && pre_case==4 && request==TIOCSWINSZ) {
        pre_ioctl_seen=1; errno=EIO; return -1;
    }
    return ioctl(fd,request,arg);
}
static int fake_open(const char *path,int flags,...) {
    if(proc_test) return open(path,flags);
    assert(!preflight);
    assert(strcmp(path,"/sys/fs/cgroup/uid_0")==0);
    return open(fake_parent,flags);
}
static int fake_openat(int parent,const char *path,int flags,...) {
    if(fail_open && !strncmp(path,"daiyu-",6)) { fail_open=0; errno=EMFILE; return -1; }
    return openat(parent,path,flags);
}
static int fake_fstat(int fd,struct stat *value) {
    if(fail_stat) { fail_stat=0; errno=EIO; return -1; }
    return fstat(fd,value);
}
static int fake_fstatat(int parent,const char *path,struct stat *value,int flags) {
    if(interrupt_stat) { interrupt_stat=0; errno=EINTR; return -1; }
    return fstatat(parent,path,value,flags);
}
static int fake_fstatfs(int fd,struct statfs *value) {
    int result=fstatfs(fd,value);
    if(!result) value->f_type=0x63677270;
    return result;
}
static int fake_mkdirat(int parent,const char *path,mode_t mode) {
    int result=mkdirat(parent,path,mode);
    if(result<0 || proc_test) return result;
    int directory=openat(parent,path,O_RDONLY|O_DIRECTORY);
    assert(directory>=0);
    const char *names[]={"cgroup.type","cgroup.procs","cgroup.kill","cgroup.events"};
    const char *values[]={"domain\n","","","populated 0\n"};
    for(int i=0;i<4;i++) {
        int fd=openat(directory,names[i],O_CREAT|O_WRONLY,0600);
        assert(fd>=0 && write(fd,values[i],strlen(values[i]))==(ssize_t)strlen(values[i]));
        close(fd);
    }
    close(directory); return 0;
}
static int fake_unlinkat(int parent,const char *path,int flags) {
    if(interrupt_unlink) { interrupt_unlink=0; errno=EINTR; return -1; }
    if(fail_unlink) { fail_unlink=0; errno=EBUSY; return -1; }
    int directory=openat(parent,path,O_RDONLY|O_DIRECTORY);
    if(directory<0) return -1;
    const char *names[]={"cgroup.type","cgroup.procs","cgroup.kill","cgroup.events"};
    for(int i=0;i<4;i++) assert(unlinkat(directory,names[i],0)==0);
    close(directory); return unlinkat(parent,path,flags);
}
static int fake_mount(const char *source,const char *target,const char *type,unsigned long flags,const void *data) {
    assert(proc_test && !strcmp(source,"proc") && !strcmp(type,"proc") && data==NULL);
    assert(flags==(MS_NOSUID|MS_NODEV|MS_NOEXEC));
    int fd=-1; assert(sscanf(target,"/proc/self/fd/%d",&fd)==1 && fd>=0);
    struct stat value; assert(fstat(fd,&value)==0 && S_ISDIR(value.st_mode));
    mount_calls++;
    if(mount_failure) { errno=EPERM; return -1; }
    return 0;
}
static uid_t fake_uid(void) { return preflight && pre_case==0 ? 1000 : 0; }
#define posix_openpt fake_posix_openpt
#define ioctl(fd,request,arg) fake_ioctl(fd,request,(void *)(arg))
#define mount fake_mount
#define main pty_main_not_run
#define open fake_open
#define openat fake_openat
#define fstat fake_fstat
#define fstatat fake_fstatat
#define fstatfs fake_fstatfs
#define mkdirat fake_mkdirat
#define unlinkat fake_unlinkat
#define getuid fake_uid
#define geteuid fake_uid
#include "../../main/kotlin/app/daiyu/android/terminal/native/daiyu_pty.c"
#undef main
#undef mount
#undef posix_openpt
#undef ioctl
#undef open
#undef openat
#undef fstat
#undef fstatat
#undef fstatfs
#undef mkdirat
#undef unlinkat
#undef getuid
#undef geteuid
static int exists(void) {
    char path[256]; snprintf(path,sizeof(path),"%s/%s",fake_parent,root_scope.name);
    struct stat value; return stat(path,&value)==0;
}
static void clean_failure_receipt(int fd) {
    unsigned char bytes[128];
    const char *receipt="DROOT0 guest=0 scope_clean=1";
    ssize_t size=read(fd,bytes,sizeof(bytes));
    assert(size==(ssize_t)(5+strlen(receipt)) && bytes[0]=='F' && be32(bytes+1)==strlen(receipt));
    assert(memcmp(bytes+5,receipt,strlen(receipt))==0);
}
static void pre_scope_failures(void) {
    for(int which=0;which<8;which++) {
        int output[2]; assert(pipe(output)==0);
        pid_t child=fork(); assert(child>=0);
        if(child==0) {
            close(output[0]); assert(dup2(output[1],STDOUT_FILENO)>=0); close(output[1]);
            preflight=1; pre_case=which;
            char *args[]={"pty","--root-scope","24","80","--","/system/bin/chroot","/fixture","/bin/sh",NULL};
            int result;
            if(which>=6) {
                root_mode=1;
                if(which==6) close(STDOUT_FILENO);
                else {
                    int broken[2]; assert(pipe(broken)==0); close(broken[0]);
                    assert(dup2(broken[1],STDOUT_FILENO)>=0); close(broken[1]);
                    assert(signal(SIGPIPE,SIG_DFL)!=SIG_ERR);
                }
                result=root_pre_scope_failure(70);
                struct sigaction observed; assert(sigaction(SIGPIPE,NULL,&observed)==0 && observed.sa_handler==SIG_IGN);
                assert(out_len>0);
            } else if(which==5) {
                char *plain[]={"pty","invalid",NULL}; result=pty_main_not_run(2,plain);
            } else {
                if(which==2) args[2]="0";
                result=pty_main_not_run(which==1 ? 2 : 8,args);
                if(which==4) assert(pre_ioctl_seen);
            }
            _exit(result);
        }
        close(output[1]);
        unsigned char bytes[128]; size_t size=0; ssize_t n;
        while((n=read(output[0],bytes+size,sizeof(bytes)-size))>0) { size+=(size_t)n; assert(size<sizeof(bytes)); }
        assert(n==0); close(output[0]);
        int status; assert(waitpid(child,&status,0)==child && WIFEXITED(status));
        int expected=which==0 ? 77 : (which==1 || which==2 || which==5) ? 64 : 70;
        assert(WEXITSTATUS(status)==expected);
        if(which>=5) assert(size==0);
        else {
            const char *receipt="DROOT0 guest=0 scope_clean=1";
            assert(size==5+strlen(receipt) && bytes[0]=='F' && be32(bytes+1)==strlen(receipt));
            assert(memcmp(bytes+5,receipt,strlen(receipt))==0);
        }
    }
}
static void proc_mount_faults(void) {
    char root[]="/tmp/daiyu-proc-test-XXXXXX"; assert(mkdtemp(root));
    char proc[256]; snprintf(proc,sizeof(proc),"%s/proc",root);
    proc_test=1; mount_calls=0;
    assert(root_mount_proc(root)==0 && mount_calls==1);
    assert(root_mount_proc(root)==0 && mount_calls==2);
    mount_failure=1; assert(root_mount_proc(root)<0 && errno==EPERM && mount_calls==3);
    mount_failure=0; assert(rmdir(proc)==0);
    assert(symlink("/tmp",proc)==0);
    assert(root_mount_proc(root)<0 && mount_calls==3); assert(unlink(proc)==0);
    int fd=open(proc,O_CREAT|O_WRONLY,0600); assert(fd>=0); close(fd);
    assert(root_mount_proc(root)<0 && mount_calls==3); assert(unlink(proc)==0);
    assert(rmdir(root)==0); proc_test=0;
}
int main(void) {
    proc_mount_faults();
    pre_scope_failures();
    strcpy(fake_parent,"/tmp/daiyu-root-scope-test-XXXXXX");
    assert(mkdtemp(fake_parent));
    int output[2]; assert(pipe(output)==0);
    int saved_stdout=dup(STDOUT_FILENO); assert(saved_stdout>=0);
    assert(dup2(output[1],STDOUT_FILENO)>=0); close(output[1]);
    root_mode=1;
    fail_open=1; assert(root_create()<0 && errno==EMFILE && !exists()); clean_failure_receipt(output[0]);
    fail_stat=1; assert(root_create()<0 && errno==EIO && !exists()); clean_failure_receipt(output[0]);
    fail_open=1; interrupt_unlink=1;
    assert(root_create()<0 && errno==EMFILE && !exists()); clean_failure_receipt(output[0]);
    fail_open=1; fail_unlink=1;
    assert(root_create()<0 && errno==EBUSY && exists());
    unsigned char transcript[2048];
    ssize_t size=read(output[0],transcript,sizeof(transcript));
    assert(size>10 && transcript[0]=='S');
    size_t next=5+be32(transcript+1);
    assert(next+9==(size_t)size && transcript[next]=='E');
    int parent=open(fake_parent,O_RDONLY|O_DIRECTORY);
    assert(parent>=0 && fake_unlinkat(parent,root_scope.name,AT_REMOVEDIR)==0); close(parent);
    assert(root_create()==0);
    assert(frame('S',root_scope.name,strlen(root_scope.name))==0);
    assert(root_start_failed(-1,-1,70,EAGAIN)==70 && !exists());
    size=read(output[0],transcript,sizeof(transcript));
    assert(size>0 && transcript[0]=='S'); next=5+be32(transcript+1);
    assert(next+9<(size_t)size && transcript[next]=='E' && be32(transcript+next+5)==EAGAIN);
    next+=9; assert(transcript[next]=='D' && next+5+be32(transcript+next+1)==(size_t)size);
    for(int which=0;which<2;which++) {
        assert(root_create()==0);
        if(which==0) interrupt_stat=1; else interrupt_unlink=1;
        assert(root_finish(-1,-1,0)==0 && !exists());
        assert(!interrupt_stat && !interrupt_unlink);
        size=read(output[0],transcript,sizeof(transcript));
        assert(size>5 && transcript[0]=='D' && 5+be32(transcript+1)==(size_t)size);
    }
    assert(dup2(saved_stdout,STDOUT_FILENO)>=0); close(saved_stdout); close(output[0]);
    assert(rmdir(fake_parent)==0);
    puts("root_scope_faults: 20 passed"); return 0;
}
