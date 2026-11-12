/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 Neo-Desktop
 *
 * ssh_jni.c - libdmodemssh for the Android app: the JNI side of
 *             org.dmodem.app.android.ssh.NativeSsh (each dmc_ssh a long,
 *             strings as Java's, bytes as byte[]).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "dmodemssh.h"

#define FN(name) Java_org_dmodem_app_android_ssh_NativeSsh_##name

/* a Java string as UTF-8, to release with done(); NULL for null */
static const char *str(JNIEnv *env, jstring s)
{
	return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

/* str()'s string released */
static void done(JNIEnv *env, jstring s, const char *c)
{
	if (s && c)
		(*env)->ReleaseStringUTFChars(env, s, c);
}

/* the handle as the connection */
static dmc_ssh *S(jlong h)
{
	return (dmc_ssh *)(intptr_t)h;
}

/* a C string (or NULL) as a Java string */
static jstring jstr(JNIEnv *env, const char *s)
{
	return s ? (*env)->NewStringUTF(env, s) : NULL;
}

/* bytes as a byte[] */
static jbyteArray jbytes(JNIEnv *env, const char *p, int n)
{
	jbyteArray a = (*env)->NewByteArray(env, n);
	if (a && n)
		(*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)p);
	return a;
}

/* NativeSsh.new: a connection, or 0 */
JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass c, jstring host, jint port, jstring user)
{
	const char *h = str(env, host), *u = str(env, user);
	dmc_ssh *s = dmc_ssh_new(h, port, u);
	(void)c;
	done(env, host, h);
	done(env, user, u);
	return (jlong)(intptr_t)s;
}

/* NativeSsh.free */
JNIEXPORT void JNICALL FN(free)(JNIEnv *env, jclass c, jlong h)
{
	(void)env;
	(void)c;
	dmc_ssh_free(S(h));
}

/* NativeSsh.option: 0, or -1 */
JNIEXPORT jint JNICALL FN(option)(JNIEnv *env, jclass c, jlong h, jstring name, jstring value)
{
	const char *n = str(env, name), *v = str(env, value);
	int r = dmc_ssh_option(S(h), n, v ? v : "");
	(void)c;
	done(env, name, n);
	done(env, value, v);
	return r;
}

/* NativeSsh.poll: 1 with the event's type, channel and value in ev[0..2]
   (its data returned: null if none), 0 on a timeout, -1 when over */
JNIEXPORT jbyteArray JNICALL FN(poll)(JNIEnv *env, jclass c, jlong h, jint ms, jintArray ev)
{
	dmc_ssh_event e;
	jint v[4] = { 0, 0, 0, 0 };
	int r = dmc_ssh_poll(S(h), ms, &e);
	(void)c;
	v[0] = r;
	if (r == 1) {
		v[1] = e.type;
		v[2] = e.channel;
		v[3] = e.value;
	}
	(*env)->SetIntArrayRegion(env, ev, 0, 4, v);
	return r == 1 && e.data ? jbytes(env, e.data, e.len) : NULL;
}

/* NativeSsh.disconnect */
JNIEXPORT void JNICALL FN(disconnect)(JNIEnv *env, jclass c, jlong h)
{
	(void)env;
	(void)c;
	dmc_ssh_disconnect(S(h));
}

/* NativeSsh.trust */
JNIEXPORT void JNICALL FN(trust)(JNIEnv *env, jclass c, jlong h, jboolean ok, jboolean remember)
{
	(void)env;
	(void)c;
	dmc_ssh_trust(S(h), ok, remember);
}

/* NativeSsh.authPassword */
JNIEXPORT void JNICALL FN(authPassword)(JNIEnv *env, jclass c, jlong h, jstring pw)
{
	const char *p = str(env, pw);
	(void)c;
	dmc_ssh_auth_password(S(h), p ? p : "");
	done(env, pw, p);
}

/* NativeSsh.authKey: 0, or -1 if the key can't be read */
JNIEXPORT jint JNICALL FN(authKey)(JNIEnv *env, jclass c, jlong h, jstring key, jstring pass)
{
	const char *k = str(env, key), *p = str(env, pass);
	int r = dmc_ssh_auth_key(S(h), k, p);
	(void)c;
	done(env, key, k);
	done(env, pass, p);
	return r;
}

/* NativeSsh.authInteractive */
JNIEXPORT void JNICALL FN(authInteractive)(JNIEnv *env, jclass c, jlong h)
{
	(void)env;
	(void)c;
	dmc_ssh_auth_interactive(S(h));
}

/* NativeSsh.authCancel */
JNIEXPORT void JNICALL FN(authCancel)(JNIEnv *env, jclass c, jlong h)
{
	(void)env;
	(void)c;
	dmc_ssh_auth_cancel(S(h));
}

/* NativeSsh.instruction */
JNIEXPORT jstring JNICALL FN(instruction)(JNIEnv *env, jclass c, jlong h)
{
	(void)c;
	return jstr(env, dmc_ssh_instruction(S(h)));
}

/* NativeSsh.prompt: the question; echo[0] whether its answer may show */
JNIEXPORT jstring JNICALL FN(prompt)(JNIEnv *env, jclass c, jlong h, jint i, jbooleanArray echo)
{
	int e = 0;
	const char *q = dmc_ssh_prompt(S(h), i, &e);
	jboolean b = e != 0;
	(void)c;
	if (echo)
		(*env)->SetBooleanArrayRegion(env, echo, 0, 1, &b);
	return jstr(env, q);
}

/* NativeSsh.answer */
JNIEXPORT void JNICALL FN(answer)(JNIEnv *env, jclass c, jlong h, jint i, jstring a)
{
	const char *s = str(env, a);
	(void)c;
	dmc_ssh_answer(S(h), i, s ? s : "");
	done(env, a, s);
}

/* NativeSsh.answered */
JNIEXPORT void JNICALL FN(answered)(JNIEnv *env, jclass c, jlong h)
{
	(void)env;
	(void)c;
	dmc_ssh_answered(S(h));
}

/* NativeSsh.shell: a channel, or -1 */
JNIEXPORT jint JNICALL FN(shell)(JNIEnv *env, jclass c, jlong h, jstring term, jint cols, jint rows, jboolean agent)
{
	const char *t = str(env, term);
	int r = dmc_ssh_shell(S(h), t, cols, rows, agent);
	(void)c;
	done(env, term, t);
	return r;
}

/* NativeSsh.exec: a channel, or -1 */
JNIEXPORT jint JNICALL FN(exec)(JNIEnv *env, jclass c, jlong h, jstring cmd, jboolean agent)
{
	const char *t = str(env, cmd);
	int r = dmc_ssh_exec(S(h), t, agent);
	(void)c;
	done(env, cmd, t);
	return r;
}

/* NativeSsh.direct: a channel, or -1 */
JNIEXPORT jint JNICALL FN(direct)(JNIEnv *env, jclass c, jlong h, jstring host, jint port, jstring from, jint fromPort)
{
	const char *t = str(env, host), *f = str(env, from);
	int r = dmc_ssh_direct(S(h), t, port, f, fromPort);
	(void)c;
	done(env, host, t);
	done(env, from, f);
	return r;
}

/* NativeSsh.listen: a request's number, or -1 */
JNIEXPORT jint JNICALL FN(listen)(JNIEnv *env, jclass c, jlong h, jstring addr, jint port)
{
	const char *a = str(env, addr);
	int r = dmc_ssh_listen(S(h), a, port);
	(void)c;
	done(env, addr, a);
	return r;
}

/* NativeSsh.unlisten */
JNIEXPORT void JNICALL FN(unlisten)(JNIEnv *env, jclass c, jlong h, jstring addr, jint port)
{
	const char *a = str(env, addr);
	(void)c;
	dmc_ssh_unlisten(S(h), a, port);
	done(env, addr, a);
}

/* NativeSsh.write: the bytes still kept for the server, or -1 if the channel isn't open */
JNIEXPORT jint JNICALL FN(write)(JNIEnv *env, jclass c, jlong h, jint ch, jbyteArray data, jint off, jint len)
{
	jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
	int r;
	(void)c;
	if (!b)
		return -1;
	r = dmc_ssh_write(S(h), ch, (const char *)b + off, len);
	(*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
	return r;
}

/* NativeSsh.eof */
JNIEXPORT void JNICALL FN(eof)(JNIEnv *env, jclass c, jlong h, jint ch)
{
	(void)env;
	(void)c;
	dmc_ssh_eof(S(h), ch);
}

/* NativeSsh.close */
JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass c, jlong h, jint ch)
{
	(void)env;
	(void)c;
	dmc_ssh_close(S(h), ch);
}

/* NativeSsh.resize */
JNIEXPORT void JNICALL FN(resize)(JNIEnv *env, jclass c, jlong h, jint ch, jint cols, jint rows)
{
	(void)env;
	(void)c;
	dmc_ssh_resize(S(h), ch, cols, rows);
}

/* NativeSsh.keyExport: the key in OpenSSH's form under a new passphrase, or null */
JNIEXPORT jstring JNICALL FN(keyExport)(JNIEnv *env, jclass c, jstring key, jstring pass, jstring newPass)
{
	const char *k = str(env, key), *p = str(env, pass), *n = str(env, newPass);
	char *out = NULL;
	jstring r = NULL;
	(void)c;
	if (dmc_ssh_key_export(k, p, n, &out) == 0)
		r = jstr(env, out);
	dmc_ssh_free_string(out);
	done(env, key, k);
	done(env, pass, p);
	done(env, newPass, n);
	return r;
}
