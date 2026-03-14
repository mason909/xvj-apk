#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <sys/ioctl.h>

JNIEXPORT int JNICALL open_port(JNIEnv *env, jobject thiz, jstring path, jint flags) {
    int fd;
    const char *path_str = (*env)->GetStringUTFChars(env, path, NULL);
    
    fd = open(path_str, O_RDWR | O_NOCTTY | O_NONBLOCK | flags);
    
    (*env)->ReleaseStringUTFChars(env, path, path_str);
    
    if (fd < 0) {
        return -1;
    }
    
    // 确保不是终端设备
    if (isatty(fd)) {
        struct termios options;
        tcgetattr(fd, &options);
        
        // 设置原始模式
        cfmakeraw(&options);
        
        // 设置波特率
        cfsetispeed(&options, B9600);
        cfsetospeed(&options, B9600);
        
        // 8N1 (8位数据, 无校验, 1位停止位)
        options.c_cflag &= ~PARENB;
        options.c_cflag &= ~CSTOPB;
        options.c_cflag &= ~CSIZE;
        options.c_cflag |= CS8;
        
        // 启用接收和设置本地模式
        options.c_cflag |= (CLOCAL | CREAD);
        
        // 设置流控制
        options.c_cflag &= ~CRTSCTS;
        
        // 设置VMIN和VTIME
        options.c_cc[VMIN] = 0;
        options.c_cc[VTIME] = 0;
        
        tcflush(fd, TCIFLUSH);
        tcsetattr(fd, TCSANOW, &options);
    }
    
    return fd;
}

JNIEXPORT void JNICALL close_port(JNIEnv *env, jobject thiz) {
    // close handled by FileDescriptor
}

JNIEXPORT jint JNICALL setup_port(JNIEnv *env, jobject thiz, jint fd, jint baudrate, jint flags) {
    if (fd < 0) return -1;
    
    struct termios options;
    tcgetattr(fd, &options);
    
    // 设置波特率
    speed_t speed;
    switch(baudrate) {
        case 9600: speed = B9600; break;
        case 19200: speed = B19200; break;
        case 38400: speed = B38400; break;
        case 57600: speed = B57600; break;
        case 115200: speed = B115200; break;
        case 230400: speed = B230400; break;
        case 460800: speed = B460800; break;
        case 921600: speed = B921600; break;
        default: speed = B9600; break;
    }
    
    cfsetispeed(&speed, speed);
    cfsetospeed(&speed, speed);
    
    // 8N1
    options.c_cflag &= ~PARENB;
    options.c_cflag &= ~CSTOPB;
    options.c_cflag &= ~CSIZE;
    options.c_cflag |= CS8;
    options.c_cflag |= (CLOCAL | CREAD);
    
    tcsetattr(fd, TCSANOW, &options);
    
    return 0;
}
