#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define TAG "wuad-mihomo"

JNIEXPORT jint JNICALL
Java_app_wuad_core_MihomoNative_start(JNIEnv *env, jclass clazz, jstring jbin, jstring jwork, jint fd) {
    const char *bin = (*env)->GetStringUTFChars(env, jbin, NULL);
    const char *work = (*env)->GetStringUTFChars(env, jwork, NULL);
    pid_t pid = fork();
    if (pid == 0) {
        int fl = fcntl(fd, F_GETFL);
        if (fl >= 0) {
            fcntl(fd, F_SETFL, fl | O_NONBLOCK);
        }
        int flags = fcntl(fd, F_GETFD);
        if (flags >= 0) {
            fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
        }
        if (chdir(work) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "chdir: %s", strerror(errno));
        }
        char logpath[512];
        snprintf(logpath, sizeof(logpath), "%s/mihomo.log", work);
        int lfd = open(logpath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (lfd >= 0) {
            dup2(lfd, STDOUT_FILENO);
            dup2(lfd, STDERR_FILENO);
            if (lfd > 2) close(lfd);
        }
        execl(bin, bin, "-d", work, (char *) NULL);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "execl %s: %s", strerror(errno));
        _exit(127);
    }
    (*env)->ReleaseStringUTFChars(env, jbin, bin);
    (*env)->ReleaseStringUTFChars(env, jwork, work);
    return (jint) pid;
}

JNIEXPORT void JNICALL
Java_app_wuad_core_MihomoNative_stop(JNIEnv *env, jclass clazz, jint pid) {
    if (pid > 1) kill((pid_t) pid, SIGTERM);
}

JNIEXPORT void JNICALL
Java_app_wuad_core_MihomoNative_kill(JNIEnv *env, jclass clazz, jint pid) {
    if (pid > 1) kill((pid_t) pid, SIGKILL);
}
