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
#include <unistd.h>
static char fake_parent[128];
static int fail_open,fail_stat,fail_unlink,interrupt_unlink;
static int fake_open(const char *path,int flags,...) {
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
static int fake_fstatfs(int fd,struct statfs *value) {
    int result=fstatfs(fd,value);
    if(!result) value->f_type=0x63677270;
    return result;
}
static int fake_mkdirat(int parent,const char *path,mode_t mode) {
    int result=mkdirat(parent,path,mode);
    if(result<0) return result;
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
static uid_t fake_uid(void) { return 0; }
#define main pty_main_not_run
#define open fake_open
#define openat fake_openat
#define fstat fake_fstat
#define fstatfs fake_fstatfs
#define mkdirat fake_mkdirat
#define unlinkat fake_unlinkat
#define getuid fake_uid
#define geteuid fake_uid
#include "../../main/kotlin/app/daiyu/android/terminal/native/daiyu_pty.c"
#undef main
#undef open
#undef openat
#undef fstat
#undef fstatfs
#undef mkdirat
#undef unlinkat
#undef getuid
#undef geteuid
static int exists(void) {
    char path[256]; snprintf(path,sizeof(path),"%s/%s",fake_parent,root_scope.name);
    struct stat value; return stat(path,&value)==0;
}
int main(void) {
    strcpy(fake_parent,"/tmp/daiyu-root-scope-test-XXXXXX");
    assert(mkdtemp(fake_parent));
    int output[2]; assert(pipe(output)==0);
    int saved_stdout=dup(STDOUT_FILENO); assert(saved_stdout>=0);
    assert(dup2(output[1],STDOUT_FILENO)>=0); close(output[1]);
    root_mode=1;
    fail_open=1; assert(root_create()<0 && errno==EMFILE && !exists());
    fail_stat=1; assert(root_create()<0 && errno==EIO && !exists());
    fail_open=1; interrupt_unlink=1;
    assert(root_create()<0 && errno==EMFILE && !exists());
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
    assert(dup2(saved_stdout,STDOUT_FILENO)>=0); close(saved_stdout); close(output[0]);
    assert(rmdir(fake_parent)==0);
    puts("root_scope_faults: 5 passed"); return 0;
}
