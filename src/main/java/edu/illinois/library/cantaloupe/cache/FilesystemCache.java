package edu.illinois.library.cantaloupe.cache;

import edu.illinois.library.cantaloupe.config.Configuration;
import edu.illinois.library.cantaloupe.config.Key;
import edu.illinois.library.cantaloupe.image.Identifier;
import edu.illinois.library.cantaloupe.image.Info;
import edu.illinois.library.cantaloupe.operation.OperationList;
import edu.illinois.library.cantaloupe.util.StringUtil;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * <p>Cache using a filesystem, storing source images, derivative images,
 * and infos in separate subdirectories.</p>
 *
 * <p>The tree structure looks like:</p>
 *
 * <ul>
 *     <li>{@link Key#FILESYSTEMCACHE_PATHNAME}/
 *         <ul>
 *             <li>source/
 *                 <ul>
 *                     <li>Intermediate subdirectories (see [1])
 *                         <ul>
 *                             <li>{identifier hash (see [2])} (see [3])</li>
 *                         </ul>
 *                     </li>
 *                 </ul>
 *             </li>
 *             <li>image/
 *                 <ul>
 *                     <li>Intermediate subdirectories (see [1])
 *                         <ul>
 *                             <li>{identifier hash (see [2])}{operation list
 *                             string representation}.{derivative format
 *                             extension} (see [3])</li>
 *                         </ul>
 *                     </li>
 *                 </ul>
 *             </li>
 *             <li>info/
 *                 <ul>
 *                     <li>Intermediate subdirectories (see [1])
 *                         <ul>
 *                             <li>{identifier hash (see [2])}.json (see
 *                             [3])</li>
 *                         </ul>
 *                     </li>
 *                 </ul>
 *             </li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <ol>
 *     <li>Subdirectories are based on identifier MD5 hash, configurable by
 *     {@link Key#FILESYSTEMCACHE_DIRECTORY_DEPTH} and
 *     {@link Key#FILESYSTEMCACHE_DIRECTORY_NAME_LENGTH}</li>
 *     <li>The hash algorithm is specified by {@link #HASH_ALGORITHM}.</li>
 *     <li>Identifiers in filenames are hashed in order to allow for identifiers
 *     longer than the filesystem's filename length limit.</li>
 *     <li>Cache files are created with a .tmp extension and moved into place
 *     when closed for writing.</li>
 * </ol>
 */
class FilesystemCache implements SourceCache, DerivativeCache {

    /**
     * Used by {@link Files#walkFileTree} to delete all temporary (*.tmp) and
     * zero-byte files within a directory tree.
     */
    private static class CacheCleaner extends SimpleFileVisitor<Path> {

        private static final Logger logger = LoggerFactory.
                getLogger(CacheCleaner.class);

        private final PathMatcher matcher;
        private long minCleanableAge;
        private int numDeleted = 0;

        CacheCleaner(long minCleanableAge) {
            this.minCleanableAge = minCleanableAge;
            matcher = FileSystems.getDefault().
                    getPathMatcher("glob:*" + TEMP_EXTENSION);
        }

        private void delete(File file) {
            try {
                FileUtils.forceDelete(file);
                numDeleted++;
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }

        private void done() {
            logger.info("Cleaned {} files.", numDeleted);
        }

        private void test(Path path) {
            // Since the instance visits only files, this will always be a file.
            final File file = path.toFile();

            // Try to avoid matching temp files that may still be open for
            // writing by assuming that files last modified long enough ago
            // are closed.
            if (System.currentTimeMillis() - file.lastModified() > minCleanableAge) {
                // Delete temp files.
                if (matcher.matches(path.getFileName())) {
                    delete(file);
                } else {
                    // Delete zero-byte files.
                    try {
                        if (Files.size(path) == 0) {
                            delete(file);
                        }
                    } catch (IOException e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }

        @Override
        public FileVisitResult visitFile(Path file,
                                         BasicFileAttributes attrs) {
            test(file);
            return java.nio.file.FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            logger.warn("visitFileFailed(): {}", e.getMessage());
            return java.nio.file.FileVisitResult.CONTINUE;
        }
    }

    /**
     * Returned by {@link FilesystemCache#newDerivativeImageOutputStream} when
     * an image can be cached. Points to a temp file that will be moved into
     * place when closed.
     */
    private static class ConcurrentFileOutputStream extends FileOutputStream {

        private static final Logger logger = LoggerFactory.
                getLogger(ConcurrentFileOutputStream.class);

        private File destinationFile;
        private Set imagesBeingWritten;
        private boolean isClosed = false;
        private Object toRemove;
        private File tempFile;

        /**
         * @param tempFile Pathname of the temp file to write to.
         * @param destinationFile Pathname to move tempFile to when it is done
         *                        being written.
         * @param imagesBeingWritten Set of OperationLists for all images
         *                           currently being written.
         * @param toRemove Object to remove from the set when done.
         * @throws FileNotFoundException
         */
        @SuppressWarnings("unchecked")
        ConcurrentFileOutputStream(File tempFile,
                                   File destinationFile,
                                   Set imagesBeingWritten,
                                   Object toRemove)
                throws FileNotFoundException {
            super(tempFile);
            imagesBeingWritten.add(toRemove);
            this.tempFile = tempFile;
            this.destinationFile = destinationFile;
            this.imagesBeingWritten = imagesBeingWritten;
            this.toRemove = toRemove;
        }

        @Override
        public void close() throws IOException {
            // This check prevents double-closing. Clients will frequently wrap
            // this stream in a TeeOutputStream, which they can't close due to
            // Restlet's OutputRepresentation.write() API contract, so it (the
            // tee stream) gets detected and double-closed by the finalizer.
            // See ImageRepresentation.write() for more info.
            if (!isClosed) {
                isClosed = true;
                try {
                    try {
                        // Close super in order to release its handle on
                        // tempFile.
                        logger.debug("close(): closing stream for {}", toRemove);
                        super.close();
                    } catch (IOException e) {
                        logger.warn("close(): {}", e.getMessage());
                    }

                    if (tempFile.length() > 0) {
                        logger.debug("close(): moving {} to {}",
                                tempFile, destinationFile.getName());
                        FileUtils.moveFile(tempFile, destinationFile);
                    } else {
                        logger.debug("close(): deleting zero-byte file: {}",
                                tempFile);
                        FileUtils.forceDelete(tempFile);
                    }
                } catch (IOException e) {
                    logger.warn("close(): {}", e.getMessage(), e);
                } finally {
                    imagesBeingWritten.remove(toRemove);
                }
            }
        }

    }

    /**
     * No-op dummy stream returned by various FilesystemCache methods when an
     * identical output stream has been returned in another thread but has not
     * yet been closed. Enables that thread to keep writing without
     * interference and without requiring clients to check for null.
     */
    private static class NullOutputStream extends OutputStream {

        @Override
        public void close() throws IOException {
            super.close();
        }

        @Override
        public void write(int b) throws IOException {
            // noop
        }

    }

    private static final Logger logger = LoggerFactory.
            getLogger(FilesystemCache.class);

    // Algorithm used for hashing identifiers to create filenames & pathnames.
    // Will be passed to MessageDigest.getInstance(). MD5 is chosen for its
    // practical combination of efficiency and collision resistance.
    // N.B. filenameSafe() must be updated when changing this.
    private static final String HASH_ALGORITHM = "MD5";
    private static final String SOURCE_IMAGE_FOLDER = "source";
    private static final String DERIVATIVE_IMAGE_FOLDER = "image";
    private static final String INFO_FOLDER = "info";

    private static final String INFO_EXTENSION = ".json";
    private static final String TEMP_EXTENSION = ".tmp";

    /** Set of operation lists for which image files are currently being
     * written from any thread. */
    private final Set<OperationList> derivativeImagesBeingWritten =
            new ConcurrentSkipListSet<>();

    /** Set of Operations for which image files are currently being purged by
     * purge(OperationList) from any thread. */
    private final Set<OperationList> imagesBeingPurged =
            new ConcurrentSkipListSet<>();

    /** Set of identifiers for which info files are currently being purged by
     * purge(Identifier) from any thread. */
    private final Set<Identifier> infosBeingPurged =
            new ConcurrentSkipListSet<>();

    private long minCleanableAge = 1000 * 60 * 10;

    /** Set of identifiers for which image files are currently being written
     * from any thread. */
    private final Set<Identifier> sourceImagesBeingWritten =
            new ConcurrentSkipListSet<>();

    /** Toggled by purge() and purgeExpired(). */
    private final AtomicBoolean globalPurgeInProgress =
            new AtomicBoolean(false);

    /** Several different lock objects for context-dependent synchronization.
     * Reduces contention for the instance. */
    private final Object imagePurgeLock = new Object();
    private final Object infoPurgeLock = new Object();
    private final Object sourceImageWriteLock = new Object();

    /** Rather than using a global lock, per-identifier locks allow for
     * simultaneous writes to different infos. Map entries are added on demand
     * and never removed. */
    private final Map<Identifier,ReadWriteLock> infoLocks =
            new ConcurrentHashMap<>();

    /**
     * @param uniqueString String from which to derive the path.
     * @return Directory path composed of fragments of a hash of the given
     *         string.
     */
    static String getHashedStringBasedSubdirectory(String uniqueString) {
        final StringBuilder path = new StringBuilder();
        try {
            final MessageDigest digest =
                    MessageDigest.getInstance(HASH_ALGORITHM);
            digest.update(uniqueString.getBytes(Charset.forName("UTF8")));
            final String sum = Hex.encodeHexString(digest.digest());

            final Configuration config = Configuration.getInstance();
            final int depth = config.getInt(Key.FILESYSTEMCACHE_DIRECTORY_DEPTH, 3);
            final int nameLength =
                    config.getInt(Key.FILESYSTEMCACHE_DIRECTORY_NAME_LENGTH, 2);

            for (int i = 0; i < depth; i++) {
                final int offset = i * nameLength;
                path.append(File.separator);
                path.append(sum.substring(offset, offset + nameLength));
            }
        } catch (NoSuchAlgorithmException e) {
            logger.error(e.getMessage(), e);
        }
        return path.toString();
    }

    private static long getLastAccessTime(File file) {
        try {
            return ((FileTime) Files.getAttribute(file.toPath(), "lastAccessTime")).
                    toMillis();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return file.lastModified();
        }
    }

    /**
     * @param file File to check.
     * @return Whether the given file is expired based on
     *         {@link Key#CACHE_SERVER_TTL} and its last-accessed time. If
     *         {@link Key#CACHE_SERVER_TTL} is 0, <code>false</code> will be
     *         returned.
     */
    private static boolean isExpired(File file) {
        final long ttlMsec = 1000 * Configuration.getInstance().
                getLong(Key.CACHE_SERVER_TTL, 0);
        final long age = System.currentTimeMillis() - getLastAccessTime(file);
        return ttlMsec > 0 && file.isFile() && age > ttlMsec;
    }

    /**
     * @return Pathname of the root cache folder.
     * @throws CacheException if {@link Key#FILESYSTEMCACHE_PATHNAME} is
     *         undefined.
     */
    static String rootPathname() throws CacheException {
        final String pathname = Configuration.getInstance().
                getString(Key.FILESYSTEMCACHE_PATHNAME);
        if (pathname == null) {
            throw new CacheException(Key.FILESYSTEMCACHE_PATHNAME +
                    " is undefined.");
        }
        return pathname;
    }

    /**
     * @return Pathname of the derivative image cache folder, or null if
     *         {@link Key#FILESYSTEMCACHE_PATHNAME} is not set.
     * @throws CacheException
     */
    static String rootDerivativeImagePathname() throws CacheException {
        return rootPathname() + File.separator + DERIVATIVE_IMAGE_FOLDER;
    }

    /**
     * @return Pathname of the image info cache folder, or null if
     *         {@link Key#FILESYSTEMCACHE_PATHNAME} is not set.
     * @throws CacheException
     */
    static String rootInfoPathname() throws CacheException {
        return rootPathname() + File.separator + INFO_FOLDER;
    }

    /**
     * @return Pathname of the source image cache folder, or null if
     *         {@link Key#FILESYSTEMCACHE_PATHNAME}
     *         is not set.
     * @throws CacheException
     */
    static String rootSourceImagePathname() throws CacheException {
        return rootPathname() + File.separator + SOURCE_IMAGE_FOLDER;
    }

    private ReadWriteLock acquireInfoLock(final Identifier identifier) {
        ReadWriteLock lock = infoLocks.get(identifier);
        if (lock == null) {
            infoLocks.putIfAbsent(identifier, new ReentrantReadWriteLock());
            lock = infoLocks.get(identifier);
        }
        return lock;
    }

    /**
     * Cleans up temp and zero-byte files.
     *
     * @throws CacheException
     */
    @Override
    public void cleanUp() throws CacheException {
        try {
            final String[] pathnamesToClean = {
                    rootSourceImagePathname(),
                    rootDerivativeImagePathname(),
                    rootInfoPathname() };
            for (String pathname : pathnamesToClean) {
                logger.info("cleanUp(): cleaning directory: {}", pathname);
                CacheCleaner cleaner = new CacheCleaner(minCleanableAge);
                Files.walkFileTree(Paths.get(pathname), cleaner);
                cleaner.done();
            }
        } catch (IOException e) {
            throw new CacheException(e.getMessage(), e);
        }
    }

    /**
     * Returns a File corresponding to the given operation list.
     *
     * @param ops Operation list identifying the file.
     * @return    File corresponding to the given operation list.
     */
    File derivativeImageFile(OperationList ops) throws CacheException {
        final String cacheRoot = StringUtils.stripEnd(
                rootDerivativeImagePathname(), File.separator);
        final String subfolderPath = StringUtils.stripEnd(
                getHashedStringBasedSubdirectory(ops.getIdentifier().toString()),
                File.separator);
        return new File(cacheRoot + subfolderPath + File.separator +
                ops.toFilename());
    }

    /**
     * @param identifier
     * @return All image files deriving from the image with the given
     *         identifier.
     * @throws CacheException
     */
    Collection<File> derivativeImageFiles(Identifier identifier)
            throws CacheException {
        final File cacheFolder = new File(rootDerivativeImagePathname() +
                getHashedStringBasedSubdirectory(identifier.toString()));
        final File[] files = cacheFolder.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(
                        StringUtil.filesystemSafe(identifier.toString()));
            }
        });
        ArrayList<File> fileList;
        if (files != null && files.length > 0) {
            fileList = new ArrayList<>(Arrays.asList(files));
        } else {
            fileList = new ArrayList<>();
        }
        return fileList;
    }

    /**
     * @param ops Operation list identifying the file.
     * @return Temp file corresponding to the given operation list. Clients
     *         should delete it when they are done with it.
     */
    File derivativeImageTempFile(OperationList ops) throws CacheException {
        return new File(derivativeImageFile(ops).getAbsolutePath() + "_" +
                Thread.currentThread().getName() + TEMP_EXTENSION);
    }

    @Override
    public Info getImageInfo(Identifier identifier) throws CacheException {
        final ReadWriteLock lock = acquireInfoLock(identifier);
        lock.readLock().lock();

        try {
            final File cacheFile = infoFile(identifier);
            if (cacheFile != null && cacheFile.exists()) {
                if (!isExpired(cacheFile)) {
                    logger.info("getImageInfo(): hit: {}",
                            cacheFile.getAbsolutePath());
                    return Info.fromJSON(cacheFile);
                } else {
                    logger.info("getImageInfo(): deleting stale file: {}",
                            cacheFile.getAbsolutePath());
                    // TODO: contention here (probably rare though)
                    if (!cacheFile.delete()) {
                        logger.warn("getImageInfo(): unable to delete {}",
                                cacheFile.getAbsolutePath());
                    }
                }
            }
        } catch (FileNotFoundException e) {
            logger.info(e.getMessage(), e);
        } catch (IOException e) {
            throw new CacheException(e.getMessage(), e);
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    @Override
    public File getSourceImageFile(Identifier identifier) throws CacheException {
        synchronized (sourceImageWriteLock) {
            while (sourceImagesBeingWritten.contains(identifier)) {
                try {
                    sourceImageWriteLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        File file = null;
        final File cacheFile = sourceImageFile(identifier);
        if (cacheFile != null && cacheFile.exists()) {
            if (!isExpired(cacheFile)) {
                logger.info("getSourceImageFile(): hit: {} ({})",
                        identifier, cacheFile.getAbsolutePath());
                file = cacheFile;
            } else {
                logger.info("getSourceImageFile(): deleting stale file: {}",
                        cacheFile.getAbsolutePath());
                if (!cacheFile.delete()) {
                    logger.warn("getSourceImageFile(): unable to delete {}",
                            cacheFile.getAbsolutePath());
                }
            }
        }
        return file;
    }

    /**
     * @param identifier
     * @return Info file corresponding to the image with the given identifier.
     */
    File infoFile(final Identifier identifier) throws CacheException {
        final String cacheRoot =
                StringUtils.stripEnd(rootInfoPathname(), File.separator);
        final String subfolderPath = StringUtils.stripEnd(
                getHashedStringBasedSubdirectory(identifier.toString()),
                File.separator);
        final String pathname = cacheRoot + subfolderPath + File.separator +
                StringUtil.filesystemSafe(identifier.toString()) +
                INFO_EXTENSION;
        return new File(pathname);
    }

    /**
     * @param identifier
     * @return Temporary info file corresponding to the image with the given
     *         identifier.
     */
    File infoTempFile(final Identifier identifier) throws CacheException {
        return new File(infoFile(identifier).getAbsolutePath() + "_" +
                Thread.currentThread().getName() + TEMP_EXTENSION);
    }

    @Override
    public InputStream newDerivativeImageInputStream(OperationList ops)
            throws CacheException {
        InputStream inputStream = null;
        final File cacheFile = derivativeImageFile(ops);
        if (cacheFile != null && cacheFile.exists()) {
            if (!isExpired(cacheFile)) {
                try {
                    logger.info("newDerivativeImageInputStream(): hit: {} ({})",
                            ops, cacheFile.getAbsolutePath());
                    inputStream = new FileInputStream(cacheFile);
                } catch (FileNotFoundException e) {
                    logger.error(e.getMessage(), e);
                }
            } else {
                logger.info("newDerivativeImageInputStream(): deleting stale file: {}",
                        cacheFile.getAbsolutePath());
                if (!cacheFile.delete()) {
                    logger.warn("newDerivativeImageInputStream(): unable to delete {}",
                            cacheFile.getAbsolutePath());
                }
            }
        }
        return inputStream;
    }

    /**
     * @param ops Operation list representing the image to write to.
     * @return An output stream to write to. The stream will generally write to
     *         a temp file and then move it into place when closed. It may also
     *         write to nothing if an output stream for the same operation list
     *         has been returned to another thread but not yet closed.
     * @throws CacheException If anything goes wrong.
     */
    @Override
    public OutputStream newDerivativeImageOutputStream(OperationList ops)
            throws CacheException {
        // If the image is being written in another thread, it will
        // be present in the derivativeImagesBeingWritten set. If so,
        // return a null output stream to avoid interfering.
        if (derivativeImagesBeingWritten.contains(ops)) {
            logger.info("newDerivativeImageOutputStream(OperationList): miss, " +
                    "but cache file for {} is being written in " +
                    "another thread, so not caching", ops);
            return new NullOutputStream();
        } else {
            logger.info("newDerivativeImageOutputStream(OperationList): miss; " +
                    "caching {}", ops);

            final File tempFile = derivativeImageTempFile(ops);

            // No need to check the return value. If anything went wrong,
            // the client will find out about it shortly. :D
            tempFile.getParentFile().mkdirs();

            // ops will be removed from this set when the returned output
            // stream is closed.
            derivativeImagesBeingWritten.add(ops);
            try {
                return new ConcurrentFileOutputStream(tempFile,
                        derivativeImageFile(ops),
                        derivativeImagesBeingWritten, ops);
            } catch (IOException e) {
                throw new CacheException(e.getMessage(), e);
            }
        }
    }

    @Override
    public OutputStream newSourceImageOutputStream(Identifier identifier)
            throws CacheException {
        // If the image is being written in another thread, it may (or may not)
        // be present in the sourceImagesBeingWritten set. If so, return a null
        // output stream to avoid interfering.
        if (sourceImagesBeingWritten.contains(identifier)) {
            logger.info("newSourceImageOutputStream(Identifier): miss, but cache " +
                    "file for {} is being written in another thread, so not " +
                    "caching", identifier);
            return new NullOutputStream();
        }

        // identifier will be removed from this set when the non-null output
        // stream returned by this method is closed.
        sourceImagesBeingWritten.add(identifier);

        logger.info("newSourceImageOutputStream(Identifier): miss; caching {}",
                identifier);
        try {
            final File tempFile = sourceImageTempFile(identifier);
            if (!tempFile.getParentFile().isDirectory()) {
                if (!tempFile.getParentFile().mkdirs()) {
                    logger.info("newSourceImageOutputStream(Identifier): can't create {}",
                            tempFile.getParentFile());
                    // We could threw a CacheException here, but it is probably
                    // not necessary as we are likely to get here often during
                    // concurrent invocations.
                    return new NullOutputStream();
                }
            }
            final File destFile = sourceImageFile(identifier);
            return new ConcurrentFileOutputStream(
                    tempFile, destFile, sourceImagesBeingWritten, identifier);
        } catch (IOException e) {
            throw new CacheException(e.getMessage(), e);
        }
    }

    /**
     * <p>Crawls the cache directory, deleting all files (but not folders)
     * within it (including temp files).</p>
     *
     * <p>Will do nothing and return immediately if a global purge is in
     * progress in another thread.</p>
     *
     * @throws CacheException
     */
    @Override
    public void purge() throws CacheException {
        if (globalPurgeInProgress.get()) {
            logger.info("purge() called with a purge already in progress. " +
                    "Aborting.");
            return;
        }
        synchronized (imagePurgeLock) {
            while (!imagesBeingPurged.isEmpty()) {
                try {
                    imagePurgeLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        try {
            globalPurgeInProgress.set(true);

            final String[] pathnamesToPurge = {
                    rootSourceImagePathname(),
                    rootDerivativeImagePathname(),
                    rootInfoPathname() };
            for (String pathname : pathnamesToPurge) {
                try {
                    logger.info("purge(): purging {}...", pathname);
                    FileUtils.cleanDirectory(new File(pathname));
                } catch (IllegalArgumentException e) {
                    logger.info(e.getMessage());
                } catch (IOException e) {
                    logger.warn(e.getMessage());
                }
            }
        } finally {
            globalPurgeInProgress.set(false);
            synchronized (imagePurgeLock) {
                imagePurgeLock.notifyAll();
            }
        }
    }

    /**
     * <p>Deletes all files associated with the given operation list.</p>
     *
     * <p>If purging is in progress in another thread, this method will wait
     * for it to finish before proceeding.</p>
     *
     * <p>Will do nothing and return immediately if a global purge is in
     * progress in another thread.</p>
     *
     * @throws CacheException
     */
    @Override
    public void purge(OperationList opList) throws CacheException {
        if (globalPurgeInProgress.get()) {
            logger.info("purge(OperationList) called with a global purge in " +
                    "progress. Aborting.");
            return;
        }
        synchronized (imagePurgeLock) {
            while (imagesBeingPurged.contains(opList)) {
                try {
                    imagePurgeLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        try {
            imagesBeingPurged.add(opList);
            logger.info("purge(OperationList): purging {}...", opList);

            File file = derivativeImageFile(opList);
            if (file != null && file.exists()) {
                try {
                    FileUtils.forceDelete(file);
                } catch (IOException e) {
                    logger.warn("purge(OperationList(): unable to delete {}",
                            file);
                }
            }
        } finally {
            imagesBeingPurged.remove(opList);
            synchronized (imagePurgeLock) {
                imagePurgeLock.notifyAll();
            }
        }
    }

    /**
     * <p>Crawls the image directory, deleting all expired files within it
     * (temporary or not), and then does the same in the info directory.</p>
     *
     * <p>Will do nothing and return immediately if a global purge is in
     * progress in another thread.</p>
     *
     * @throws CacheException
     */
    @Override
    public void purgeExpired() throws CacheException {
        if (globalPurgeInProgress.get()) {
            logger.info("purgeExpired() called with a purge in progress. " +
                    "Aborting.");
            return;
        }
        synchronized (imagePurgeLock) {
            while (!imagesBeingPurged.isEmpty()) {
                try {
                    imagePurgeLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        logger.info("purgeExpired(): purging...");

        try {
            globalPurgeInProgress.set(true);
            final String imagePathname = rootDerivativeImagePathname();
            final String infoPathname = rootInfoPathname();

            long imageCount = 0;
            final File imageDir = new File(imagePathname);
            Iterator<File> it = FileUtils.iterateFiles(imageDir, null, true);
            while (it.hasNext()) {
                final File file = it.next();
                if (isExpired(file)) {
                    try {
                        FileUtils.forceDelete(file);
                        imageCount++;
                    } catch (IOException e) {
                        logger.warn(e.getMessage());
                    }
                }
            }

            long infoCount = 0;
            final File infoDir = new File(infoPathname);
            it = FileUtils.iterateFiles(infoDir, null, true);
            while (it.hasNext()) {
                final File file = it.next();
                if (isExpired(file)) {
                    try {
                        FileUtils.forceDelete(file);
                        infoCount++;
                    } catch (IOException e) {
                        logger.warn(e.getMessage());
                    }
                }
            }
            logger.info("purgeExpired(): purged {} expired image(s) and {} " +
                    "expired infos(s)", imageCount, infoCount);
        } finally {
            globalPurgeInProgress.set(false);
            synchronized (imagePurgeLock) {
                imagePurgeLock.notifyAll();
            }
        }
    }

    /**
     * <p>Deletes all files associated with the given identifier.</p>
     *
     * <p>Will do nothing and return immediately if a global purge is in
     * progress in another thread.</p>
     *
     * @throws CacheException
     */
    @Override
    public void purge(Identifier identifier) throws CacheException {
        if (globalPurgeInProgress.get()) {
            logger.info("purge(Identifier) called with a global purge in " +
                    "progress. Aborting.");
            return;
        }
        synchronized (infoPurgeLock) {
            while (infosBeingPurged.contains(identifier)) {
                try {
                    infoPurgeLock.wait();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        try {
            infosBeingPurged.add(identifier);
            logger.info("purge(Identifier): purging {}...", identifier);

            // Delete the source image
            final File sourceFile = sourceImageFile(identifier);
            try {
                logger.info("purge(Identifier): deleting {}", sourceFile);
                FileUtils.forceDelete(sourceFile);
            } catch (FileNotFoundException e) {
                // This is not really a problem, and probably more likely to
                // happen than not.
                logger.info("purge(Identifier): no source image for {}",
                        sourceFile);
            } catch (IOException e) {
                logger.warn(e.getMessage());
            }
            // Delete derivative images
            for (File imageFile : derivativeImageFiles(identifier)) {
                try {
                    logger.info("purge(Identifier): deleting {}", imageFile);
                    FileUtils.forceDelete(imageFile);
                } catch (IOException e) {
                    logger.warn(e.getMessage());
                }
            }
            // Delete the info
            final File infoFile = infoFile(identifier);
            try {
                logger.info("purge(Identifier): deleting {}", infoFile);
                FileUtils.forceDelete(infoFile);
            } catch (FileNotFoundException e) {
                // This is not a problem, and as likely to happen as not.
                logger.info("purge(Identifier): no info for {}", infoFile);
            } catch (IOException e) {
                logger.warn(e.getMessage());
            }
        } finally {
            infosBeingPurged.remove(identifier);
        }
    }

    @Override
    public void put(Identifier identifier, Info imageInfo)
            throws CacheException {
        final ReadWriteLock lock = acquireInfoLock(identifier);
        lock.writeLock().lock();

        logger.info("put(): caching: {}", identifier);

        final File destFile = infoFile(identifier);
        final File tempFile = infoTempFile(identifier);

        try {
            if (!tempFile.getParentFile().exists() &&
                    !tempFile.getParentFile().mkdirs()) {
                throw new IOException("Unable to create directory: " +
                        tempFile.getParentFile());
            }

            FileUtils.writeStringToFile(tempFile, imageInfo.toJSON());

            logger.debug("put(): moving {} to {}",
                    tempFile, destFile.getName());
            Files.move(tempFile.toPath(), destFile.toPath());
        } catch (FileAlreadyExistsException e) {
            // When this method is called concurrently for the same info, one
            // invocation will complete successfully and the other(s) will
            // produce this, which is fine.
            logger.debug("put(): {}", e.getMessage());
        } catch (IOException e) {
            tempFile.delete();
            throw new CacheException(e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Sets the age threshold for cleaning files. Cleanable files last
     * modified less than this many milliseconds ago will not be subject to
     * cleanup.
     *
     * @param age Age in milliseconds.
     */
    void setMinCleanableAge(long age) {
        minCleanableAge = age;
    }

    /**
     * Returns a File corresponding to the given identifier.
     *
     * @param identifier Identifier identifying the file.
     * @return File corresponding to the given identifier.
     */
    File sourceImageFile(Identifier identifier) throws CacheException {
        final String cacheRoot = StringUtils.stripEnd(
                rootSourceImagePathname(), File.separator);
        final String subfolderPath = StringUtils.stripEnd(
                getHashedStringBasedSubdirectory(identifier.toString()),
                File.separator);
        final String baseName = cacheRoot + subfolderPath + File.separator +
                StringUtil.filesystemSafe(identifier.toString());
        return new File(baseName);
    }

    /**
     * @param identifier Identifier identifying the file.
     * @return Temp file corresponding to a source image with the given
     * identifier. Clients should delete it when they are done with it.
     */
    File sourceImageTempFile(Identifier identifier) throws CacheException {
        return new File(sourceImageFile(identifier).getAbsolutePath() + "_" +
                Thread.currentThread().getName() + TEMP_EXTENSION);
    }

}
