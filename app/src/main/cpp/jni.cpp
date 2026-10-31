/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 *    jni.cpp - the engine (engine.h) for Kotlin: the natives and the
 *              callbacks of org.dmodem.app.android.NativeModem
 *
 *    The callbacks come on the modem's and SIP's own threads, attached
 *    to the VM for as long as they run.
 *
 */

#include <jni.h>
#include <sys/socket.h>
#include <unistd.h>

#include <string>

#include "engine.h"

using dmodem_android::Config;
using dmodem_android::Engine;
using dmodem_android::Status;

namespace {

const char CLASS[] = "org/dmodem/app/android/NativeModem";

JavaVM *vm;
jclass cls;
jmethodID on_log, on_status, on_exit, on_line, on_speaker;
/* the speaker's sound: written to [0] (dropped when full), Kotlin reads [1] */
int speaker_fd[2] = { -1, -1 };

/* this thread's JNIEnv, the thread attached if it wasn't (detached
   again when it ends) */
struct Attached {
	JNIEnv *env = nullptr;
	bool ours = false;
	/* the thread detached, if attached here */
	~Attached() {
		if (ours)
			vm->DetachCurrentThread();
	}
};

/* this thread's JNIEnv, attaching it if need be (nullptr if that fails) */
JNIEnv *env_now()
{
	thread_local Attached a;
	if (a.env)
		return a.env;
	if (vm->GetEnv(reinterpret_cast<void **>(&a.env), JNI_VERSION_1_6) ==
	    JNI_OK)
		return a.env;
	if (vm->AttachCurrentThread(&a.env, nullptr) != JNI_OK) {
		a.env = nullptr;
		return nullptr;
	}
	a.ours = true;
	return a.env;
}

/* a pending exception of a callback's cleared (and left to the log) */
void clear(JNIEnv *env)
{
	if (env->ExceptionCheck()) {
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

/* the line printable: anything but printable ASCII and tab as '?' (the
   SIP messages come from the network, and NewStringUTF() takes only
   modified UTF-8), without its newline */
std::string printable(const char *line)
{
	std::string s(line);
	while (!s.empty() && (s.back() == '\n' || s.back() == '\r'))
		s.pop_back();
	for (char &c : s) {
		unsigned char u = (unsigned char)c;
		if ((u < 0x20 && u != '\t') || u >= 0x7f)
			c = '?';
	}
	return s;
}

/* a log line to NativeModem.onLog() */
void log_line(const char *line)
{
	JNIEnv *env = env_now();
	if (!env)
		return;
	jstring s = env->NewStringUTF(printable(line).c_str());
	if (!s) {
		clear(env);
		return;
	}
	env->CallStaticVoidMethod(cls, on_log, s);
	clear(env);
	env->DeleteLocalRef(s);
}

/* the call's state to NativeModem.onStatus() */
void status_changed(const Status &st)
{
	JNIEnv *env = env_now();
	if (!env)
		return;
	env->CallStaticVoidMethod(cls, on_status, (jint)st.link,
				  (jboolean)st.offhook, (jint)st.tx_rate,
				  (jint)st.rx_rate);
	clear(env);
}

/* the modem's end to NativeModem.onExit() */
void exited(int result)
{
	JNIEnv *env = env_now();
	if (!env)
		return;
	env->CallStaticVoidMethod(cls, on_exit, (jint)result);
	clear(env);
}

/* a peer line's call: its socket to Kotlin (closed here if that fails) */
void line_started(bool caller, const char *dial, int fd)
{
	JNIEnv *env = env_now();
	if (!env) {
		close(fd);
		return;
	}
	jstring d = env->NewStringUTF(printable(dial).c_str());
	if (!d) {
		clear(env);
		close(fd);
		return;
	}
	env->CallStaticVoidMethod(cls, on_line, (jboolean)caller, d, (jint)fd);
	clear(env);
	env->DeleteLocalRef(d);
}

/* the speaker's level to NativeModem.onSpeaker() */
void speaker_level(int level)
{
	JNIEnv *env = env_now();
	if (!env)
		return;
	env->CallStaticVoidMethod(cls, on_speaker, (jint)level);
	clear(env);
}

/* the sound to Kotlin's player, never waiting (the modem's thread) */
void speaker_sound(const int16_t *pcm, int n)
{
	if (speaker_fd[0] >= 0 &&
	    send(speaker_fd[0], pcm, 2 * (size_t)n,
		 MSG_DONTWAIT | MSG_NOSIGNAL) < 0) {
		/* (the player behind: this block dropped) */
	}
}

/* s as a std::string ("" for null) */
std::string str(JNIEnv *env, jstring s)
{
	if (!s)
		return std::string();
	const char *c = env->GetStringUTFChars(s, nullptr);
	std::string r(c ? c : "");
	if (c)
		env->ReleaseStringUTFChars(s, c);
	return r;
}

/* NativeModem.nativeStart(): the app's end of the DTE's socket, or -1 */
jint native_start(JNIEnv *env, jclass, jstring user, jstring password,
		  jstring domain, jint sip_port, jboolean listen, jboolean t38,
		  jstring dns, jstring country, jstring data_file,
		  jint log_level, jint sip_log_level, jboolean peer,
		  jint peer_lead_ms, jint usb_fd, jint usb_rate)
{
	Config cfg;

	cfg.user = str(env, user);
	cfg.password = str(env, password);
	cfg.domain = str(env, domain);
	cfg.sip_port = sip_port;
	cfg.listen = listen;
	cfg.t38 = t38;
	cfg.dns = str(env, dns);
	cfg.country = str(env, country);
	cfg.data_file = str(env, data_file);
	cfg.log_level = log_level;
	cfg.sip_log_level = sip_log_level;
	cfg.peer = peer;
	cfg.peer_lead_ms = peer_lead_ms;
	cfg.usb_fd = usb_fd;
	cfg.usb_rate = usb_rate;
	return Engine::instance().start(cfg, status_changed, exited,
					line_started);
}

/* NativeModem.nativeStop(): the engine stopped */
void native_stop(JNIEnv *, jclass)
{
	Engine::instance().stop();
}

/* NativeModem.nativePeerEvent(): a peer line's event to the engine */
void native_peer_event(JNIEnv *env, jclass, jstring line)
{
	Engine::instance().peer_event(str(env, line).c_str());
}

/* NativeModem.nativeSpeakerFd(): the speaker sound's end for Kotlin
   (one for the process: -1 after the first) */
jint native_speaker_fd(JNIEnv *, jclass)
{
	int fd = speaker_fd[1];
	speaker_fd[1] = -1;
	return fd;
}

/* NativeModem.nativeRunning(): whether the engine runs */
jboolean native_running(JNIEnv *, jclass)
{
	return Engine::instance().running();
}

const JNINativeMethod methods[] = {
	{ "nativeStart", "(Ljava/lang/String;Ljava/lang/String;"
	  "Ljava/lang/String;IZZLjava/lang/String;Ljava/lang/String;"
	  "Ljava/lang/String;IIZIII)I",
	  reinterpret_cast<void *>(native_start) },
	{ "nativeStop", "()V", reinterpret_cast<void *>(native_stop) },
	{ "nativeRunning", "()Z", reinterpret_cast<void *>(native_running) },
	{ "nativeSpeakerFd", "()I", reinterpret_cast<void *>(native_speaker_fd) },
	{ "nativePeerEvent", "(Ljava/lang/String;)V",
	  reinterpret_cast<void *>(native_peer_event) },
};

} // namespace

/* the class, its callbacks and natives; the log to it */
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *)
{
	JNIEnv *env;

	vm = jvm;
	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) !=
	    JNI_OK)
		return JNI_ERR;
	jclass c = env->FindClass(CLASS);
	if (!c)
		return JNI_ERR;
	cls = static_cast<jclass>(env->NewGlobalRef(c));
	env->DeleteLocalRef(c);
	on_log = env->GetStaticMethodID(cls, "onLog", "(Ljava/lang/String;)V");
	on_status = env->GetStaticMethodID(cls, "onStatus", "(IZII)V");
	on_exit = env->GetStaticMethodID(cls, "onExit", "(I)V");
	on_line = env->GetStaticMethodID(cls, "onLine",
					 "(ZLjava/lang/String;I)V");
	on_speaker = env->GetStaticMethodID(cls, "onSpeaker", "(I)V");
	if (!on_log || !on_status || !on_exit || !on_line || !on_speaker)
		return JNI_ERR;
	if (env->RegisterNatives(cls, methods,
				 sizeof(methods) / sizeof(methods[0])) < 0)
		return JNI_ERR;
	Engine::instance().set_log(log_line);
	if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, speaker_fd) == 0) {
		/* (a second's room: the player keeps up, or blocks go) */
		int size = 32 * 1024;
		setsockopt(speaker_fd[0], SOL_SOCKET, SO_SNDBUF, &size,
			   sizeof(size));
	}
	Engine::instance().set_speaker(speaker_level, speaker_sound);
	return JNI_VERSION_1_6;
}
