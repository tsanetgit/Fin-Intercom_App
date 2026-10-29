package org.tsanet.connector.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * runtime-core carries its own copy of the gateway contract ({@code org.tsanet.connector.tsanet}),
 * and on this module's classpath that copy loads ahead of {@code connector-core}, which arrives
 * with gateway-engine. The engine is compiled against connector-core, so a member it uses that
 * the copy lacks fails at runtime, as {@code NoSuchMethodError}, {@code NoSuchFieldError} or
 * {@code AbstractMethodError} (tsanetgit/Fin-Intercom_App#103). This test compares the two
 * copies of every contract type runtime-core holds, nested types such as an enum included, each
 * loaded in isolation from its own classpath root, against the connector-core gateway-engine
 * actually brings:
 * <ul>
 *   <li>a class, record or enum in the copy must have every public constructor, method and
 *       field connector-core's has (the engine constructs and reads them, and an enum's
 *       constants are its public fields), and the same superclass;</li>
 *   <li>an interface in the copy may declare only abstract methods connector-core's also
 *       declares (the engine implements connector-core's, so any other abstract method would be
 *       unimplemented; a default method is inherited, so it can't fail).</li>
 * </ul>
 * Extra members in a class, and connector-core types the copy doesn't hold, are fine: the copy
 * may run ahead of the pinned engine, and a type it doesn't hold loads from connector-core.
 *
 * <p>The interface rule is one-way on purpose. A method connector-core's interface has and the
 * copy lacks (today {@code forwardAttachments}) fails only if something calls it through the
 * interface; neither this adapter nor connector-core or gateway-engine does (a grep of their
 * {@code src/main} finds only the declaration and the engine's implementation). Calling one
 * would need the copy to catch up first, which this test would then hold.
 */
class ContractCopyParityTest {

    private static final String PACKAGE_PATH = "org/tsanet/connector/tsanet/";

    @Test
    void runtimeCoresContractCopyProvidesWhatTheEngineUses() throws Exception {
        Path copyRoot = rootOf("runtime-core");
        Path coreRoot = rootOf("connector-core");
        List<String> compared = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try (Classes copy = new Classes(copyRoot); Classes core = new Classes(coreRoot)) {
            for (String name : copy.contractClassNames()) {
                if (!core.has(name)) {
                    continue; // runtime-core's own type (its fake gateway): nothing to match
                }
                compared.add(name);
                Class<?> mine = copy.load(name);
                Class<?> theirs = core.load(name);
                if (theirs.isInterface()) {
                    Set<String> missing = new TreeSet<>(abstractSignatures(mine));
                    missing.removeAll(signatures(theirs));
                    missing.forEach(m -> problems.add(name + " declares " + m
                            + ", which connector-core's interface lacks, so the engine doesn't implement it"));
                } else {
                    Set<String> missing = new TreeSet<>(signatures(theirs));
                    missing.removeAll(signatures(mine));
                    missing.forEach(m -> problems.add(name + " lacks " + m + ", which connector-core's has"));
                    String mySuper = mine.getSuperclass() == null ? null : mine.getSuperclass().getName();
                    String theirSuper = theirs.getSuperclass() == null ? null : theirs.getSuperclass().getName();
                    if (!java.util.Objects.equals(mySuper, theirSuper)) {
                        problems.add(name + " extends " + mySuper + "; connector-core's extends " + theirSuper);
                    }
                }
            }
        }
        // A vacuous pass would hide drift: make sure the comparison really ran.
        assertThat(compared).contains("org.tsanet.connector.tsanet.dto.SubmissionDraft",
                "org.tsanet.connector.tsanet.TsanetGateway", "org.tsanet.connector.tsanet.GatewayException",
                "org.tsanet.connector.tsanet.dto.InboundCase$Status");
        assertThat(problems).as("runtime-core's contract copy against connector-core (" + compared.size()
                + " types compared)").isEmpty();
    }

    @Test
    void anEnumsConstantsAreAmongWhatIsCompared() {
        // Fin references every Status constant, so removing one from the copy can't be
        // probed without breaking compilation; this pins that constants are compared at all.
        assertThat(signatures(org.tsanet.connector.tsanet.dto.InboundCase.Status.class))
                .contains("field PENDINGACTION:org.tsanet.connector.tsanet.dto.InboundCase$Status");
    }

    /** Public constructors, methods and fields, as "name(param types)return type" and "field name:type". */
    private static Set<String> signatures(Class<?> type) {
        Stream<String> constructors = Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers()))
                .map(ContractCopyParityTest::signature);
        Stream<String> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                .map(ContractCopyParityTest::signature);
        Stream<String> fields = Arrays.stream(type.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && !f.isSynthetic())
                .map(f -> "field " + f.getName() + ":" + f.getType().getName());
        return Stream.of(constructors, methods, fields).flatMap(s -> s)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** An interface's abstract methods, the ones an implementation must supply. */
    private static Set<String> abstractSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(ContractCopyParityTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String signature(Constructor<?> c) {
        return "<init>(" + names(c.getParameterTypes()) + ")";
    }

    private static String signature(Method m) {
        return m.getName() + "(" + names(m.getParameterTypes()) + ")" + m.getReturnType().getName();
    }

    private static String names(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getName).collect(Collectors.joining(","));
    }

    /**
     * The classpath root (a directory or a jar) holding the contract package for the module
     * whose path names it: runtime-core's classes or jar, or the connector-core jar.
     */
    private static Path rootOf(String module) throws Exception {
        List<URL> found = new ArrayList<>();
        ClassLoader.getSystemClassLoader().getResources(PACKAGE_PATH + "GatewayException.class")
                .asIterator().forEachRemaining(found::add);
        for (URL url : found) {
            String s = url.toString();
            if (!s.contains(module)) {
                continue;
            }
            if (s.startsWith("jar:")) {
                return Path.of(new java.net.URI(s.substring(4, s.indexOf("!/"))));
            }
            Path classFile = Path.of(url.toURI());
            return classFile.getRoot().resolve(classFile.subpath(0,
                    classFile.getNameCount() - PACKAGE_PATH.split("/").length - 1));
        }
        throw new AssertionError("no " + module + " copy of the contract on the test classpath: " + found);
    }

    /** One classpath root, readable as files and loadable in isolation. */
    private static final class Classes implements AutoCloseable {
        private final Path root;
        private final FileSystem jar;
        private final Path base;
        private final URLClassLoader loader;

        Classes(Path root) throws Exception {
            this.root = root;
            this.jar = Files.isDirectory(root) ? null : FileSystems.newFileSystem(root, Map.of());
            this.base = jar == null ? root : jar.getPath("/");
            // Parent is the platform loader, so only this root supplies org.tsanet.* classes.
            this.loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }

        List<String> contractClassNames() throws Exception {
            Path dir = base.resolve(PACKAGE_PATH);
            try (Stream<Path> files = Files.walk(dir)) {
                return files.map(p -> base.relativize(p).toString().replace('\\', '/'))
                        // Nested types count (InboundCase's Direction and Status enums);
                        // anonymous classes ($1, $2) are implementation, not contract.
                        .filter(p -> p.endsWith(".class") && !p.matches(".*\\$[0-9]+.*"))
                        .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.'))
                        .sorted()
                        .toList();
            }
        }

        boolean has(String className) {
            return Files.exists(base.resolve(className.replace('.', '/') + ".class"));
        }

        Class<?> load(String className) throws ClassNotFoundException {
            return Class.forName(className, false, loader);
        }

        @Override
        public void close() throws Exception {
            loader.close();
            if (jar != null) {
                jar.close();
            }
        }

        @Override
        public String toString() {
            return root.toString();
        }
    }
}
