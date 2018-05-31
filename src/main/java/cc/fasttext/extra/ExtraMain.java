package cc.fasttext.extra;

import cc.fasttext.Main;
import cc.fasttext.extra.io.HadoopIOStreams;
import cc.fasttext.io.IOStreams;
import cc.fasttext.io.ScrollableInputStream;
import cc.fasttext.io.impl.LocalIOStreams;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Supports read/write from/to HDFS and LocalFS
 * <p>
 * Created by @szuev on 28.11.2017.
 */
@SuppressWarnings("WeakerAccess")
public class ExtraMain {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtraMain.class);

    private static final IOStreams DEFAULT_FS = new CombinedIOStreams(ExtraMain::createFS);

    public static final Map<String, String> DEBUG_HADOOP_SETTINGS = Stream.of(
            pair("io.file.buffer.size", 50 * 1024),
            pair("dfs.client.socket-timeout", 2 * 60 * 1000))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final String DEFAULT_HADOOP_USER = "hadoop";

    static {
        init();
    }

    public static void main(String... args) {
        Main.main(args);
    }

    private static void init() {
        Main.setFileSystem(getFileSystem());
    }

    public static IOStreams getFileSystem() {
        return DEFAULT_FS;
    }

    public static <K, V> Map.Entry<K, V> entry(K k, V v) {
        return new AbstractMap.SimpleEntry<>(k, v);
    }

    private static Map.Entry<String, String> pair(String k, Object o) {
        return entry(k, String.valueOf(o));
    }

    private static IOStreams createHadoopFS(String url, String user, Map<String, String> settings) throws IOException {
        LOGGER.debug("Hadoop init: host={}, user={}, settings={}", url, user, settings);
        Configuration conf = new Configuration();
        conf.set("fs.defaultFS", url);
        conf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());

        settings.forEach(conf::set);
        System.setProperty("HADOOP_USER_NAME", user);
        String home;
        // todo: hadoop.home.dir
        try {
            URL resource = ExtraMain.class.getResource("/bin");
            home = Paths.get(resource.toURI()).getParent().toString(); // wtf ?
        } catch (URISyntaxException | FileSystemNotFoundException e) {
            LOGGER.warn("Can't find hdfs-home: {}", e.getMessage());
            home = "/";
        }
        System.setProperty("hadoop.home.dir", home);
        FileSystem fs = FileSystem.get(IOStreams.toURI(url), conf);
        return new HadoopIOStreams(fs) {
            @Override
            public String toString() {
                return String.format("Hadoop-FS: %s@<%s>%s", user, url, settings);
            }

        };
    }

    private static IOStreams createHadoopFS(URI uri, Map<String, String> settings) throws IllegalArgumentException, UncheckedIOException {
        if (!"hdfs".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Not a hdfs uri: " + uri);
        }
        String user = uri.getRawUserInfo();
        if (user == null) {
            user = DEFAULT_HADOOP_USER;
        } else if (user.contains(":")) {
            throw new IllegalArgumentException("Security URIs are not supported: " + uri);
        }
        URI root;
        try {
            root = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Can't get root from " + uri, e);
        }
        try {
            return createHadoopFS(root.toString(), user, settings);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't init hadoop fs by uri " + uri, e);
        }
    }

    /**
     * The factory method to create FS.
     * Supported schemes:
     * - hdfs: hadoop file system.
     * - http: web (read only)
     * - https: web (read only)
     * - empty or file: local file system
     *
     * @param uri               {@link URI} fs root path
     * @param hadoopSettingsMap the Map with settings to init hadoop
     * @return {@link IOStreams}, fs
     */
    public static IOStreams createFS(URI uri, Map<String, String> hadoopSettingsMap) {
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            return new LocalIOStreams();
        }
        if ("hdfs".equalsIgnoreCase(scheme)) {
            return createHadoopFS(uri, hadoopSettingsMap);
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return new WebIOStreams();
        }
        throw new UnsupportedOperationException("Not supported fs: " + uri);
    }

    public static IOStreams createFS(URI uri) {
        return createFS(uri, DEBUG_HADOOP_SETTINGS);
    }

    /**
     * Created by @szuev on 28.11.2017.
     */
    public static class CombinedIOStreams implements IOStreams {
        private final Map<URI, IOStreams> map = new ConcurrentHashMap<>();
        private final Function<URI, IOStreams> provider;

        public CombinedIOStreams(Function<URI, IOStreams> provider) {
            this.provider = Objects.requireNonNull(provider, "Null fs provider.");
        }

        @Override
        public OutputStream createOutput(String uri) throws IOException {
            return chooseFS(uri).createOutput(uri);
        }

        @Override
        public InputStream openInput(String uri) throws IOException {
            return chooseFS(uri).openInput(uri);
        }

        @Override
        public boolean canRead(String uri) {
            return chooseFS(uri).canRead(uri);
        }

        @Override
        public boolean canWrite(String uri) {
            return chooseFS(uri).canWrite(uri);
        }

        @Override
        public ScrollableInputStream openScrollable(String uri) throws IOException, UnsupportedOperationException {
            return chooseFS(uri).openScrollable(uri);
        }

        @Override
        public long size(String uri) throws IOException, UnsupportedOperationException {
            return chooseFS(uri).size(uri);
        }

        private IOStreams chooseFS(String uri) {
            return map.computeIfAbsent(getRoot(uri), provider);
        }

        private static URI getRoot(String uri) {
            return IOStreams.toURI(uri).resolve("/");
        }
    }

    public static class WebIOStreams implements IOStreams {

        @Override
        public OutputStream createOutput(String uri) {
            throw new UnsupportedOperationException("Writing is prohibited: " + uri);
        }

        @Override
        public InputStream openInput(String uri) throws IOException {
            return IOStreams.toURI(uri).toURL().openStream();
        }

        @Override
        public boolean canRead(String uri) {
            return true;
        }

        @Override
        public boolean canWrite(String uri) {
            return false;
        }

    }
}
