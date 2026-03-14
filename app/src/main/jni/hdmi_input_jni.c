#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <errno.h>

#define LOG_TAG "HDMI_INPUT_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int hdmi_fd = -1;

/**
 * 打开HDMI输入设备
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_open(JNIEnv *env, jobject thiz, jstring device_path) {
    const char *path = (*env)->GetStringUTFChars(env, device_path, NULL);
    
    hdmi_fd = open(path, O_RDWR);
    
    (*env)->ReleaseStringUTFChars(env, device_path, path);
    
    if (hdmi_fd < 0) {
        LOGE("Failed to open HDMI device: %s", strerror(errno));
        return -1;
    }
    
    LOGI("HDMI device opened: %s", path);
    return hdmi_fd;
}

/**
 * 关闭HDMI输入设备
 */
JNIEXPORT void JNICALL
Java_com_xv_japp_hdmi_HdmiNative_close(JNIEnv *env, jobject thiz) {
    if (hdmi_fd >= 0) {
        close(hdmi_fd);
        hdmi_fd = -1;
        LOGI("HDMI device closed");
    }
}

/**
 * 获取HDMI输入参数
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_getParam(JNIEnv *env, jobject thiz, jint param_id) {
    if (hdmi_fd < 0) {
        return -1;
    }
    
    int value = -1;
    ioctl(hdmi_fd, VIDIOC_G_PARM, &value);
    return value;
}

/**
 * 设置HDMI输入参数
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_setParam(JNIEnv *env, jobject thiz, jint param_id, jint value) {
    if (hdmi_fd < 0) {
        return -1;
    }
    
    // 设置参数
    return ioctl(hdmi_fd, param_id, &value);
}

/**
 * 开始捕获
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_startCapture(JNIEnv *env, jobject thiz) {
    if (hdmi_fd < 0) {
        LOGE("HDMI device not opened");
        return -1;
    }
    
    // 开始视频捕获
    int ret = ioctl(hdmi_fd, VIDIOC_STREAMON, NULL);
    if (ret < 0) {
        LOGE("Failed to start capture: %s", strerror(errno));
        return -1;
    }
    
    LOGI("HDMI capture started");
    return 0;
}

/**
 * 停止捕获
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_stopCapture(JNIEnv *env, jobject thiz) {
    if (hdmi_fd < 0) {
        return -1;
    }
    
    int ret = ioctl(hdmi_fd, VIDIOC_STREAMOFF, NULL);
    LOGI("HDMI capture stopped");
    return ret;
}

/**
 * 读取帧数据
 */
JNIEXPORT jint JNICALL
Java_com_xv_japp_hdmi_HdmiNative_readFrame(JNIEnv *env, jobject thiz, jbyteArray buffer, jint size) {
    if (hdmi_fd < 0 || buffer == NULL) {
        return -1;
    }
    
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    int ret = read(hdmi_fd, buf, size);
    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    
    return ret;
}
