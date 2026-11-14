/* Host fault-test harness; production recovery code is included unchanged. */
#define main daiyu_pty_unused_main
#define WS_LABEL_ATTRIBUTE "user.daiyu_test_selinux"
#include "../../main/kotlin/app/daiyu/android/terminal/native/daiyu_pty.c"
#undef main
int main(int argc,char **argv) { return workspace_repair_main(argc,argv); }
