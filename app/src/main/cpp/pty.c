// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
//
// libdmodem_pty: a program on a pseudo-terminal of its own, for the local
// shell (com.neodesktop.dmodem.local.Pty). Plain POSIX: built for Android
// and, in the unit tests, for the host.
#define _GNU_SOURCE
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <dirent.h>
#include <poll.h>

static void throw_io(JNIEnv *env, const char *what)
{
	char msg[256];
	snprintf(msg, sizeof msg, "%s: %s", what, strerror(errno));
	jclass c = (*env)->FindClass(env, "java/io/IOException");
	if (c)
		(*env)->ThrowNew(env, c, msg);
}

/* a String[] as a NULL-ended char*[] (malloc'd; freed by free_strings) */
static char **strings(JNIEnv *env, jobjectArray a)
{
	jsize n = a ? (*env)->GetArrayLength(env, a) : 0;
	char **out = calloc((size_t)n + 1, sizeof *out);
	for (jsize i = 0; i < n; i++) {
		jstring s = (jstring)(*env)->GetObjectArrayElement(env, a, i);
		const char *c = (*env)->GetStringUTFChars(env, s, NULL);
		out[i] = strdup(c);
		(*env)->ReleaseStringUTFChars(env, s, c);
		(*env)->DeleteLocalRef(env, s);
	}
	return out;
}

static void free_strings(char **s)
{
	for (char **p = s; p && *p; p++)
		free(*p);
	free(s);
}

/*
 * start(path, argv, envp, cwd, rows, cols, pidOut): the program started
 * on a new pty, as the leader of its own session; the master's fd back
 * (its pid in pidOut[0]).
 */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_start(JNIEnv *env, jclass cls, jstring jpath,
	jobjectArray jargv, jobjectArray jenvp, jstring jcwd, jint rows, jint cols, jintArray pid_out)
{
	(void)cls;
	int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (master < 0) {
		throw_io(env, "posix_openpt");
		return -1;
	}
	char slave_name[64];
	if (grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof slave_name)) {
		throw_io(env, "pty");
		close(master);
		return -1;
	}
	// UTF-8 input (erase a whole character), and the size
	struct termios tio;
	if (tcgetattr(master, &tio) == 0) {
		tio.c_iflag |= IUTF8;
		tio.c_iflag &= ~(IXON | IXOFF);
		tcsetattr(master, TCSANOW, &tio);
	}
	struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
	ioctl(master, TIOCSWINSZ, &ws);

	// (everything the child needs made before fork: no allocation after it)
	const char *path_c = (*env)->GetStringUTFChars(env, jpath, NULL);
	char *path = strdup(path_c);
	(*env)->ReleaseStringUTFChars(env, jpath, path_c);
	char *cwd = NULL;
	if (jcwd) {
		const char *c = (*env)->GetStringUTFChars(env, jcwd, NULL);
		cwd = strdup(c);
		(*env)->ReleaseStringUTFChars(env, jcwd, c);
	}
	char **argv = strings(env, jargv);
	char **envp = strings(env, jenvp);

	pid_t pid = fork();
	if (pid < 0) {
		throw_io(env, "fork");
		close(master);
		free(path); free(cwd); free_strings(argv); free_strings(envp);
		return -1;
	}
	if (pid == 0) {
		// the child: its own session, the pty its terminal; the parent's fds closed
		setsid();
		int slave = open(slave_name, O_RDWR);
		if (slave < 0)
			_exit(127);
		ioctl(slave, TIOCSCTTY, 0);
		dup2(slave, 0);
		dup2(slave, 1);
		dup2(slave, 2);
		DIR *d = opendir("/proc/self/fd");
		if (d) {
			int dfd = dirfd(d);
			struct dirent *e;
			while ((e = readdir(d)) != NULL) {
				int fd = atoi(e->d_name);
				if (fd > 2 && fd != dfd)
					close(fd);
			}
			closedir(d);
		}
		// signals as a new program expects them (the JVM blocks and ignores some)
		sigset_t none;
		sigemptyset(&none);
		sigprocmask(SIG_SETMASK, &none, NULL);
		for (int s = 1; s < 32; s++)
			signal(s, SIG_DFL);
		if (cwd && chdir(cwd) != 0)
			chdir("/");
		execve(path, argv, envp);
		// (no shell to run: say so on the terminal)
		const char *m = "exec failed: ";
		write(2, m, strlen(m));
		write(2, path, strlen(path));
		write(2, "\r\n", 2);
		_exit(127);
	}
	free(path); free(cwd); free_strings(argv); free_strings(envp);
	jint p = pid;
	(*env)->SetIntArrayRegion(env, pid_out, 0, 1, &p);
	return master;
}

JNIEXPORT void JNICALL
Java_com_neodesktop_dmodem_local_Pty_resize(JNIEnv *env, jclass cls, jint fd, jint rows, jint cols)
{
	(void)env; (void)cls;
	struct winsize ws = { .ws_row = (unsigned short)rows, .ws_col = (unsigned short)cols };
	ioctl(fd, TIOCSWINSZ, &ws);
}

/* bytes read into b[off..off+len): their count, or -1 at the end (the program and its children gone) */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_read(JNIEnv *env, jclass cls, jint fd, jbyteArray b, jint off, jint len)
{
	(void)cls;
	char buf[16384];
	if (len > (jint)sizeof buf)
		len = sizeof buf;
	ssize_t n;
	do
		n = read(fd, buf, (size_t)len);
	while (n < 0 && errno == EINTR);
	if (n <= 0)
		return -1; // EIO: the slave's last holder has gone
	(*env)->SetByteArrayRegion(env, b, off, (jsize)n, (const jbyte *)buf);
	return (jint)n;
}

/* all of b[off..off+len) written; false if it can't be */
JNIEXPORT jboolean JNICALL
Java_com_neodesktop_dmodem_local_Pty_write(JNIEnv *env, jclass cls, jint fd, jbyteArray b, jint off, jint len)
{
	(void)cls;
	jbyte *p = (*env)->GetByteArrayElements(env, b, NULL);
	jint done = 0;
	while (done < len) {
		ssize_t n = write(fd, p + off + done, (size_t)(len - done));
		if (n < 0 && errno == EINTR)
			continue;
		if (n <= 0)
			break;
		done += (jint)n;
	}
	(*env)->ReleaseByteArrayElements(env, b, p, JNI_ABORT);
	return done == len;
}

/* the program's end waited for: its exit status, or 128 + the signal that ended it */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_waitFor(JNIEnv *env, jclass cls, jint pid)
{
	(void)env; (void)cls;
	int st;
	while (waitpid(pid, &st, 0) < 0) {
		if (errno != EINTR)
			return -1;
	}
	if (WIFEXITED(st))
		return WEXITSTATUS(st);
	if (WIFSIGNALED(st))
		return 128 + WTERMSIG(st);
	return -1;
}

/* a signal to the program's process group (its session: the shell and its jobs) */
JNIEXPORT void JNICALL
Java_com_neodesktop_dmodem_local_Pty_signal(JNIEnv *env, jclass cls, jint pid, jint sig)
{
	(void)env; (void)cls;
	if (kill(-pid, sig) != 0)
		kill(pid, sig);
}

/*
 * a signal to every process in the program's session: the shell and its
 * jobs, each job in a process group of its own (job control); a count of
 * those signalled back
 */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_signalSession(JNIEnv *env, jclass cls, jint sid, jint sig)
{
	(void)env; (void)cls;
	int count = 0;
	DIR *d = opendir("/proc");
	if (!d)
		return kill(-sid, sig) == 0;
	struct dirent *e;
	while ((e = readdir(d)) != NULL) {
		int pid = atoi(e->d_name);
		if (pid <= 0)
			continue;
		char path[64], buf[512];
		snprintf(path, sizeof path, "/proc/%d/stat", pid);
		int fd = open(path, O_RDONLY | O_CLOEXEC);
		if (fd < 0)
			continue;
		ssize_t n = read(fd, buf, sizeof buf - 1);
		close(fd);
		if (n <= 0)
			continue;
		buf[n] = 0;
		// "pid (comm) state ppid pgrp session ...": comm may hold spaces and parentheses
		char *p = strrchr(buf, ')');
		int ppid, pgrp, session;
		char state;
		if (p && sscanf(p + 1, " %c %d %d %d", &state, &ppid, &pgrp, &session) == 4 && session == sid) {
			if (kill(pid, sig) == 0)
				count++;
		}
	}
	closedir(d);
	return count;
}

JNIEXPORT void JNICALL
Java_com_neodesktop_dmodem_local_Pty_close(JNIEnv *env, jclass cls, jint fd)
{
	(void)env; (void)cls;
	close(fd);
}

/*
 * openPort(): a pty with no program on it (the modem's /dev/ttyDM0 for
 * the local Linux): its master's fd. Its slave is opened once, put in
 * raw mode (the programs that open it set their own), and closed again,
 * so the master's poll() says POLLHUP until a program has it open.
 */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_openPort(JNIEnv *env, jclass cls)
{
	(void)cls;
	int m = posix_openpt(O_RDWR | O_NOCTTY);
	if (m < 0) {
		throw_io(env, "posix_openpt");
		return -1;
	}
	fcntl(m, F_SETFD, FD_CLOEXEC);
	char name[128];
	if (grantpt(m) < 0 || unlockpt(m) < 0 || ptsname_r(m, name, sizeof name) != 0) {
		throw_io(env, "the pty's slave");
		close(m);
		return -1;
	}
	int s = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
	if (s < 0) {
		throw_io(env, name);
		close(m);
		return -1;
	}
	struct termios t;
	if (tcgetattr(s, &t) == 0) {
		cfmakeraw(&t);
		tcsetattr(s, TCSANOW, &t);
	}
	close(s);
	return m;
}

/* the slave's path of master fd (/dev/pts/N), or null */
JNIEXPORT jstring JNICALL
Java_com_neodesktop_dmodem_local_Pty_ptsName(JNIEnv *env, jclass cls, jint fd)
{
	(void)cls;
	char name[128];
	if (ptsname_r(fd, name, sizeof name) != 0)
		return NULL;
	return (*env)->NewStringUTF(env, name);
}

/* poll() on fd for reading, ms at most: its revents (POLLIN 1, POLLHUP 0x10), 0 if nothing came */
JNIEXPORT jint JNICALL
Java_com_neodesktop_dmodem_local_Pty_poll(JNIEnv *env, jclass cls, jint fd, jint ms)
{
	(void)env; (void)cls;
	struct pollfd p = { .fd = fd, .events = POLLIN, .revents = 0 };
	int n;
	do
		n = poll(&p, 1, ms);
	while (n < 0 && errno == EINTR);
	return n > 0 ? p.revents : 0;
}
