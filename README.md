This is a mirror of the OpenJDK langtools repo.  We use it to publish a binary
of javac.jar for use in error-prone (https://github.com/google/error-prone).

baseline:

 - [8057794: Compiler Error when obtaining .class property](http://hg.openjdk.java.net/jdk9/dev/langtools/rev/2f8f2ae8a806)

cherry-picks:

 - [8046762: Revert some inference fixes in JDK-8033718](http://hg.openjdk.java.net/jdk8u/jdk8u-dev/langtools/rev/1aeb322cf646)
 - [8051402: javac, type containment should accept that CAP <= ? extends CAP and CAP <= ? super CAP](http://hg.openjdk.java.net/jdk8u/jdk8u-dev/langtools/rev/91e9834baff2)
