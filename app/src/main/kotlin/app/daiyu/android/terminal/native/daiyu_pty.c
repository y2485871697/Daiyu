/* Independent Daiyu PTY helper. SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <time.h>

/* Protocol v1: type:u8, length:u32 big endian, payload. No shell-output markers.
 * stdin: W input bytes, R rows:u16/cols:u16, I interrupt foreground, C close.
 * stdout: H "DPTY1", O raw PTY output, T drain-truncated, X waitpid status:i32, E errno:i32.
 * Limits apply before allocating or dispatching. stderr is diagnostic-only.
 */
#define FRAME_MAX 65536u
#define QUEUE_MAX (4u * FRAME_MAX)
static unsigned char incoming[FRAME_MAX + 5], outgoing[QUEUE_MAX];
static size_t in_len, out_len;
static volatile sig_atomic_t interrupted;
static void on_signal(int number) { (void)number; interrupted=1; }
static uint32_t be32(const unsigned char *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static void put32(unsigned char *p, uint32_t n) {
    p[0]=(unsigned char)(n>>24); p[1]=(unsigned char)(n>>16); p[2]=(unsigned char)(n>>8); p[3]=(unsigned char)n;
}
static int frame(unsigned char type, const void *bytes, size_t n) {
    if (n > FRAME_MAX || out_len + n + 5 > QUEUE_MAX) return -1;
    outgoing[out_len]=type; put32(outgoing+out_len+1,(uint32_t)n);
    if(n) memcpy(outgoing+out_len+5,bytes,n);
    out_len+=n+5;
    return 0;
}
static void stop_child(pid_t child, int master) {
    pid_t fg=tcgetpgrp(master);
    if (fg>0 && fg!=getpgrp()) kill(-fg,SIGKILL);
    kill(-child,SIGKILL);
    kill(child,SIGKILL);
}
static int64_t millis(void) {
    struct timespec now;
    if(clock_gettime(CLOCK_MONOTONIC,&now)<0) return 0;
    return (int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}
static int positive(const char *s) {
    char *end; long n=strtol(s,&end,10);
    return *s && !*end && n>0 && n<=1000 ? (int)n : -1;
}
int main(int argc, char **argv) {
    if(argc<5 || strcmp(argv[3],"--") || argv[4][0]!='/') return 64;
    int rows=positive(argv[1]), cols=positive(argv[2]);
    if(rows<0 || cols<0) return 64;
    pid_t parent=getppid();
    struct sigaction action;
    memset(&action,0,sizeof(action)); action.sa_handler=on_signal; sigemptyset(&action.sa_mask);
    if(sigaction(SIGTERM,&action,NULL)<0 || sigaction(SIGHUP,&action,NULL)<0 || sigaction(SIGINT,&action,NULL)<0) return 70;
    if(prctl(PR_SET_PDEATHSIG,SIGTERM)<0 || getppid()!=parent) return 70;
    signal(SIGPIPE,SIG_IGN);
    int master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC|O_NONBLOCK);
    if(master<0 || grantpt(master)<0 || unlockpt(master)<0) return 70;
    char slave_name[256];
    if(ptsname_r(master,slave_name,sizeof(slave_name))!=0) return 70;
    int exec_error[2];
    if(pipe2(exec_error,O_CLOEXEC)<0) return 70;
    struct winsize size={(unsigned short)rows,(unsigned short)cols,0,0};
    if(ioctl(master,TIOCSWINSZ,&size)<0) return 70;
    pid_t child=fork();
    if(child<0) return 70;
    if(child==0) {
        close(exec_error[0]); close(master);
        int saved=0;
        if(setsid()<0) goto child_error;
        int slave=open(slave_name,O_RDWR|O_NOCTTY);
        if(slave<0 || ioctl(slave,TIOCSCTTY,0)<0) goto child_error;
        for(int fd=0;fd<3;fd++) if(dup2(slave,fd)<0) goto child_error;
        if(slave>2) close(slave);
        signal(SIGPIPE,SIG_DFL); signal(SIGTERM,SIG_DFL); signal(SIGHUP,SIG_DFL); signal(SIGINT,SIG_DFL);
        execv(argv[4],argv+4);
child_error:
        saved=errno;
        (void)!write(exec_error[1],&saved,sizeof(saved));
        _exit(127);
    }
    close(exec_error[1]);
    int start_error=0; ssize_t err_bytes;
    do { err_bytes=read(exec_error[0],&start_error,sizeof(start_error)); } while(err_bytes<0 && errno==EINTR && !interrupted);
    close(exec_error[0]);
    if(err_bytes!=0) {
        stop_child(child,master); waitpid(child,NULL,0); close(master);
        unsigned char error_frame[9]={'E',0,0,0,4}; put32(error_frame+5,(uint32_t)(start_error ? start_error : EIO));
        (void)!write(STDOUT_FILENO,error_frame,sizeof(error_frame)); return 71;
    }
    if(fcntl(STDIN_FILENO,F_SETFL,O_NONBLOCK)<0 || fcntl(STDOUT_FILENO,F_SETFL,O_NONBLOCK)<0) {
        stop_child(child,master); waitpid(child,NULL,0); close(master); return 70;
    }
    frame('H',"DPTY1",5);
    unsigned char pending[QUEUE_MAX]; size_t pending_at=0,pending_len=0;
    int64_t drain_deadline=0;
    int child_status=0,child_done=0,eof=0,exit_sent=0,failed=0;
    while(!failed) {
        if(interrupted) { failed=1; break; }
        if(!child_done) {
            siginfo_t info;
            memset(&info,0,sizeof(info));
            if(waitid(P_PID,(id_t)child,&info,WEXITED|WNOHANG|WNOWAIT)<0) {
                if(errno!=EINTR) { failed=1; break; }
            } else if(info.si_pid==child) {
                child_done=1;
                child_status=info.si_code==CLD_EXITED ? info.si_status<<8 : info.si_status | (info.si_code==CLD_DUMPED ? 128 : 0);
                // Keep the leader unreaped until cleanup; never signal a recycled child PID.
                stop_child(child,master);
                drain_deadline=millis()+250;
                pending_at=pending_len=0;
            }
        }
        if(child_done && !eof && millis()>=drain_deadline && out_len+5<=QUEUE_MAX) {
            frame('T',NULL,0); eof=1;
        }
        if(child_done && eof && !exit_sent && out_len+9<=QUEUE_MAX) {
            unsigned char status[4]; put32(status,(uint32_t)child_status);
            frame('X',status,4); exit_sent=1;
        }
        if(exit_sent && out_len==0) break;
        struct pollfd fds[3]={
            {STDIN_FILENO, (short)((!child_done && in_len<sizeof(incoming)) ? POLLIN : 0),0},
            {master,(short)((!eof && out_len+8197<=QUEUE_MAX ? POLLIN : 0) | (pending_at<pending_len ? POLLOUT : 0)),0},
            {STDOUT_FILENO,(short)(out_len ? POLLOUT : 0),0}
        };
        if(!fds[0].events) fds[0].fd=-1;
        if(!fds[1].events) fds[1].fd=-1;
        if(poll(fds,3,100)<0) { if(errno==EINTR) continue; failed=1; break; }
        if(fds[2].revents&(POLLERR|POLLHUP|POLLNVAL)) { failed=1; break; }
        if(out_len && fds[2].revents&POLLOUT) {
            ssize_t n=write(STDOUT_FILENO,outgoing,out_len);
            if(n>0) { memmove(outgoing,outgoing+n,out_len-(size_t)n); out_len-=(size_t)n; }
            else if(n<0 && errno!=EAGAIN && errno!=EINTR) { failed=1; break; }
        }
        if(pending_at<pending_len && fds[1].revents&POLLOUT) {
            ssize_t n=write(master,pending+pending_at,pending_len-pending_at);
            if(n>0) pending_at+=(size_t)n;
            else if(n<0 && errno!=EAGAIN && errno!=EINTR) { failed=1; break; }
        }
        if(!eof && out_len+8197<=QUEUE_MAX && fds[1].revents&(POLLIN|POLLHUP|POLLERR)) {
            unsigned char bytes[8192]; ssize_t n=read(master,bytes,sizeof(bytes));
            if(n>0) frame('O',bytes,(size_t)n);
            else if(n==0 || (n<0 && errno==EIO)) eof=1;
            else if(errno!=EAGAIN && errno!=EINTR) { failed=1; break; }
        }
        if(!child_done && fds[0].revents&(POLLIN|POLLHUP|POLLERR)) {
            ssize_t n=read(STDIN_FILENO,incoming+in_len,sizeof(incoming)-in_len);
            if(n==0) { failed=1; break; }
            if(n>0) in_len+=(size_t)n;
            else if(errno!=EAGAIN && errno!=EINTR) { failed=1; break; }
        }
        while(!child_done && in_len>=5) {
            uint32_t n=be32(incoming+1); unsigned char type=incoming[0];
            if(n>FRAME_MAX) { failed=1; break; }
            if(in_len<n+5u) break;
            if(type=='W' && n) {
                if(pending_at) { memmove(pending,pending+pending_at,pending_len-pending_at); pending_len-=pending_at; pending_at=0; }
                if(pending_len+n>QUEUE_MAX) { failed=1; break; }
                memcpy(pending+pending_len,incoming+5,n); pending_len+=n;
            }
            else if(type=='R' && n==4) {
                size.ws_row=(unsigned short)((incoming[5]<<8)|incoming[6]);
                size.ws_col=(unsigned short)((incoming[7]<<8)|incoming[8]);
                if(size.ws_row<1 || size.ws_row>1000 || size.ws_col<1 || size.ws_col>1000 || ioctl(master,TIOCSWINSZ,&size)<0) { failed=1; break; }
            } else if(type=='I' && n==0) {
                pid_t fg=tcgetpgrp(master);
                if(fg>0 && fg!=getpgrp()) kill(-fg,SIGINT);
            } else if(type=='C' && n==0) { failed=1; break; }
            else if(!(type=='W' && n==0)) { failed=1; break; }
            memmove(incoming,incoming+n+5,in_len-n-5); in_len-=n+5;
        }
    }
    if(failed || !child_done) stop_child(child,master);
    close(master);
    while(waitpid(child,NULL,0)<0 && errno==EINTR) {}
    return failed ? 74 : 0;
}
