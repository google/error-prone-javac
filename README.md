This is a mirror of the OpenJDK langtools repo.

We use it to publish a binary of javac.jar for use in Error Prone
(https://github.com/google/error-prone).

The current release is based on OpenJDK 9+181

Backports:

*   [JDK-8175794](https://bugs.openjdk.java.net/browse/JDK-8175794): Type
    inference regression after JDK-8078093
*   [JDK-8182649](https://bugs.openjdk.java.net/browse/JDK-8182649): Unable to
    integrate due to compilation error
*   [JDK-8144185](https://bugs.openjdk.java.net/browse/JDK-8144185): javac
    produces incorrect RuntimeInvisibleTypeAnnotations length attribute
*   [JDK-8181464](https://bugs.openjdk.java.net/browse/JDK-8181464): Invalid
    lambda in annotation causes NPE in Lint.augment
*   [JDK-8187247](https://bugs.openjdk.java.net/browse/JDK-8187247): canonical
    import check compares classes by simple name
*   [JDK-8007720](https://bugs.openjdk.java.net/browse/JDK-8007720): Names are
    not loaded correctly for method parameters if the parameters have
    annotations
*   [JDK-8177486](https://bugs.openjdk.java.net/browse/JDK-8177486): Incorrect
    handling of mandated parameter names in MethodParameters attributes

Other changes

*   Default to source/target 8
*   Add a flag (`-XDallowBetterNullChecks=false`) to suppress the use of
    Objects.requireNonNull
*   Don't canonicalize archive paths
*   Disable indy string concatenation by default
*   Avoid some duplicate bridge methods
*   Fix `BY_FILE` compilation policy
*   Fix `-verbose` logging
*   Unbreak javadoc -encoding
*   SimpleFileObject inconsistency between getName and getShortName
