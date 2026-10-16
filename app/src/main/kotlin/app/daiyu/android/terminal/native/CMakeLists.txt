cmake_minimum_required(VERSION 3.22)
project(daiyu_terminal C)

# This remains an executable (with the NDK's CRT entry point), NOT a JNI shared
# library with main used as an ELF entry point.
add_executable(daiyu_pty daiyu_pty.c)
target_compile_features(daiyu_pty PRIVATE c_std_11)
target_compile_options(daiyu_pty PRIVATE -Wall -Wextra -Werror)
if(ANDROID)
    # Android's package installer extracts only lib/<abi>/lib*.so. AGP explicitly
    # selects this executable target; the suffix is a delivery convention only.
    set_target_properties(daiyu_pty PROPERTIES
        OUTPUT_NAME "libdaiyu_pty"
        SUFFIX ".so"
        POSITION_INDEPENDENT_CODE TRUE
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    target_link_options(daiyu_pty PRIVATE -pie -Wl,-z,max-page-size=16384)
endif()
