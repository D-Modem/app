plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin's Gradle plugin on the classpath: AGP's built-in Kotlin compiles with this version
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// Each run's test logs: build/test-runs/<date_time>/<module>-<task>/<class>/<test>.log, what each
// test printed (the line tools' logs among it), as it printed it. A task's folder goes once it ends
// with nothing failed, the run's once it's empty: what's left is the runs to look at.
val testRunStamp: String = java.time.LocalDateTime.now()
    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
val testRunsDir = layout.buildDirectory.dir("test-runs").get().asFile
val testRunDir = File(testRunsDir, testRunStamp)

subprojects {
    tasks.withType<AbstractTestTask>().configureEach {
        val dir = File(testRunDir, "${project.name}-$name")
        if (this is Test) systemProperty("dmodem.testRunDir", dir.path)
        val writers = java.util.concurrent.ConcurrentHashMap<String, java.io.Writer>()
        /** [s] safe as a file name: other characters as _, at most 150 */
        fun fileName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(150)
        /** the log [d]'s output goes to (its class's _class.log if it's no test), opened once */
        fun writer(d: TestDescriptor): java.io.Writer {
            val cls = d.className ?: d.parent?.className ?: "_run"
            val key = if (d.className != null && d.name != d.className) "${fileName(cls.substringAfterLast('.'))}/${fileName(d.name)}.log"
            else "${fileName(cls.substringAfterLast('.'))}/_class.log"
            return writers.computeIfAbsent(key) {
                File(dir, it).apply { parentFile.mkdirs() }.bufferedWriter()
            }
        }
        doFirst { dir.deleteRecursively() }
        addTestOutputListener(object : TestOutputListener {
            /** what a test printed, into its log as it comes */
            override fun onOutput(d: TestDescriptor, e: TestOutputEvent) {
                val w = writer(d)
                synchronized(w) {
                    w.write(e.message)
                    w.flush()
                }
            }
        })
        addTestListener(object : TestListener {
            /** (nothing before a suite) */
            override fun beforeSuite(suite: TestDescriptor) {}
            /** (nothing before a test) */
            override fun beforeTest(d: TestDescriptor) {}
            /** a failed test's stack traces at the end of its log */
            override fun afterTest(d: TestDescriptor, r: TestResult) {
                // (a failure's stack trace at the end of its log)
                if (r.resultType == TestResult.ResultType.FAILURE) {
                    val w = writer(d)
                    synchronized(w) {
                        w.write("\n---- FAILED ----\n")
                        r.exceptions.forEach { w.write(it.stackTraceToString()) }
                        w.flush()
                    }
                }
            }
            /** at the run's end the logs closed, and the task's folder removed if nothing failed (else where it is said) */
            override fun afterSuite(suite: TestDescriptor, r: TestResult) {
                if (suite.parent != null) return
                writers.values.forEach { runCatching { it.close() } }
                writers.clear()
                if (r.failedTestCount == 0L && r.resultType != TestResult.ResultType.FAILURE) {
                    dir.deleteRecursively()
                    if (testRunDir.list()?.isEmpty() == true) testRunDir.delete()
                } else {
                    logger.warn("test logs: ${dir.path}")
                }
            }
        })
    }
}
