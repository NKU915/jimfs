/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.io.jimfs;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.io.jimfs.file.LinkHandling.FOLLOW_LINKS;
import static com.google.common.io.jimfs.file.LinkHandling.NOFOLLOW_LINKS;
import static com.google.common.io.jimfs.util.ExceptionHelpers.requireNonNull;
import static com.google.common.io.jimfs.util.ExceptionHelpers.throwProviderMismatch;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.jimfs.bytestore.ByteStore;
import com.google.common.io.jimfs.config.JimfsConfiguration;
import com.google.common.io.jimfs.config.UnixConfiguration;
import com.google.common.io.jimfs.file.File;
import com.google.common.io.jimfs.file.FileCreator;
import com.google.common.io.jimfs.file.FileTree;
import com.google.common.io.jimfs.file.LinkHandling;
import com.google.common.io.jimfs.path.JimfsPath;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

/**
 * @author Colin Decker
 */
public final class JimfsFileSystemProvider extends FileSystemProvider {

  public static final String SCHEME = "jimfs";

  @Override
  public String getScheme() {
    return SCHEME;
  }

  private final ConcurrentMap<URI, JimfsFileSystem> fileSystems = new ConcurrentHashMap<>();

  @Override
  public JimfsFileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
    JimfsConfiguration config = new UnixConfiguration("/work", "root", "root", "rw-r--r--");
    JimfsFileSystem fileSystem = new RealJimfsFileSystem(this, config);
    if (fileSystems.putIfAbsent(uri, fileSystem) != null) {
      throw new FileSystemAlreadyExistsException(uri.toString());
    }
    return fileSystem;
  }

  @Override
  public JimfsFileSystem getFileSystem(URI uri) {
    JimfsFileSystem fileSystem = fileSystems.get(uri);
    if (fileSystem == null) {
      throw new FileSystemNotFoundException(uri.toString());
    }
    return fileSystem;
  }

  @Override
  public Path getPath(URI uri) {
    try {
      URI withoutPath = new URI(
          uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
      return getFileSystem(withoutPath).getPath(uri.getPath());
    } catch (URISyntaxException e) {
      throw new AssertionError();
    }
  }

  private static JimfsPath checkPath(Path path) {
    if (path instanceof JimfsPath) {
      return (JimfsPath) path;
    }
    throw throwProviderMismatch(path);
  }

  /**
   * Returns the file tree to use for the given path.
   */
  public static FileTree getFileTree(JimfsPath path) {
    return path.getFileSystem().getFileTree(path);
  }

  @Nullable
  private static File lookup(Path path, LinkHandling linkHandling) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    FileTree tree = getFileTree(checkedPath);
    return tree.lookupFile(checkedPath, linkHandling);
  }

  @Override
  public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    options = getOptionsForChannel(options);
    return getByteStore(path, options, attrs).openFileChannel(options);
  }

  @Override
  public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
      FileAttribute<?>... attrs) throws IOException {
    return newFileChannel(path, options, attrs);
  }

  @Override
  public AsynchronousFileChannel newAsynchronousFileChannel(Path path,
      Set<? extends OpenOption> options, ExecutorService executor, FileAttribute<?>... attrs)
      throws IOException {
    options = getOptionsForChannel(options);
    return getByteStore(path, options, attrs).openAsynchronousFileChannel(executor, options);
  }

  @Override
  public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
    return getByteStore(path, getOptionsForRead(options)).openInputStream();
  }

  @Override
  public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
    ImmutableSet<OpenOption> optionsSet = getOptionsForWrite(options);
    return getByteStore(path, optionsSet).openOutputStream(optionsSet);
  }

  public static Set<? extends OpenOption> getOptionsForChannel(Set<? extends OpenOption> options) {
    if (!options.contains(READ) && !options.contains(WRITE)) {
      OpenOption optionToAdd = options.contains(APPEND) ? WRITE : READ;
      return ImmutableSet.<OpenOption>builder()
          .addAll(options)
          .add(optionToAdd)
          .build();
    }
    return options;
  }

  static ImmutableSet<OpenOption> getOptionsForRead(OpenOption... options) {
    ImmutableSet<OpenOption> optionsSet = ImmutableSet.copyOf(options);
    if (optionsSet.isEmpty()) {
      optionsSet = ImmutableSet.<OpenOption>of(READ);
    } else if (optionsSet.contains(WRITE)) {
      throw new UnsupportedOperationException("WRITE");
    }

    return optionsSet;
  }

  static ImmutableSet<OpenOption> getOptionsForWrite(OpenOption... options) {
    ImmutableSet<OpenOption> optionsSet = ImmutableSet.copyOf(options);
    if (optionsSet.isEmpty()) {
      optionsSet = ImmutableSet.<OpenOption>of(CREATE, WRITE, TRUNCATE_EXISTING);
    } else if (optionsSet.contains(READ)) {
      throw new UnsupportedOperationException("READ");
    }
    return optionsSet;
  }

  /**
   * Gets the byte store for the regular file at the given path, throwing an exception if it
   * doesn't exist or isn't a regular file.
   */
  private ByteStore getByteStore(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    return getFileTree(checkedPath).getByteStore(checkedPath, options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(Path dir,
      DirectoryStream.Filter<? super Path> filter) throws IOException {
    JimfsPath checkedPath = checkPath(dir);
    return getFileTree(checkedPath)
        .newSecureDirectoryStream(checkedPath, filter, LinkHandling.FOLLOW_LINKS);
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    JimfsPath checkedPath = checkPath(dir);
    FileTree tree = getFileTree(checkedPath);
    FileCreator createDirectory = checkedPath.getFileSystem().getFileService()
        .directoryCreator(attrs);
    tree.createFile(checkedPath, createDirectory, false);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    JimfsPath linkPath = checkPath(link);
    JimfsPath existingPath = checkPath(existing);
    checkArgument(linkPath.getFileSystem().equals(existingPath.getFileSystem()),
        "link and existing paths must belong to the same file system instance");
    FileTree tree = getFileTree(linkPath);
    tree.link(linkPath, getFileTree(existingPath), existingPath);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    JimfsPath linkPath = checkPath(link);
    JimfsPath targetPath = checkPath(target);
    checkArgument(linkPath.getFileSystem().equals(targetPath.getFileSystem()),
        "link and target paths must belong to the same file system instance");
    FileTree tree = getFileTree(linkPath);
    FileCreator createSymbolicLink = linkPath.getFileSystem().getFileService()
        .symbolicLinkCreator(targetPath, attrs);
    tree.createFile(linkPath, createSymbolicLink, false);
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    File file = requireNonNull(lookup(link, NOFOLLOW_LINKS), link);
    if (!file.isSymbolicLink()) {
      throw new NotLinkException(link.toString());
    }
    return (Path) file.content();
  }

  @Override
  public void delete(Path path) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    FileTree tree = getFileTree(checkedPath);
    tree.deleteFile(checkedPath);
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    JimfsPath sourcePath = checkPath(source);
    JimfsPath targetPath = checkPath(target);

    FileTree sourceTree = getFileTree(sourcePath);
    FileTree targetTree = getFileTree(targetPath);
    sourceTree.copyFile(sourcePath, targetTree, targetPath, ImmutableSet.copyOf(options));
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    JimfsPath sourcePath = checkPath(source);
    JimfsPath targetPath = checkPath(target);

    FileTree sourceTree = getFileTree(sourcePath);
    FileTree targetTree = getFileTree(targetPath);
    sourceTree.moveFile(sourcePath, targetTree, targetPath, ImmutableSet.copyOf(options));
  }

  @Override
  public boolean isSameFile(Path path, Path path2) throws IOException {
    if (path.equals(path2)) {
      return true;
    }

    if (!(path instanceof JimfsPath && path2 instanceof JimfsPath)) {
      return false;
    }

    JimfsPath checkedPath = (JimfsPath) path;
    JimfsPath checkedPath2 = (JimfsPath) path2;

    FileTree tree = getFileTree(checkedPath);
    FileTree tree2 = getFileTree(checkedPath2);

    return tree.isSameFile(checkedPath, tree2, checkedPath2);
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    return checkPath(path).getFileSystem().configuration()
        .isHidden(path);
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    return null;
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    requireNonNull(lookup(checkedPath, FOLLOW_LINKS), path);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
      LinkOption... options) {
    JimfsPath checkedPath = checkPath(path);
    return getFileTree(checkedPath)
        .getFileAttributeView(checkedPath, type, LinkHandling.fromOptions(options));
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
      LinkOption... options) throws IOException {
    JimfsPath checkedPath = checkPath(path);
    return getFileTree(checkedPath)
        .readAttributes(checkedPath, type, LinkHandling.fromOptions(options));
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    JimfsPath checkedPath = checkPath(path);
    return getFileTree(checkedPath)
        .readAttributes(checkedPath, attributes, LinkHandling.fromOptions(options));
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    JimfsPath checkedPath = checkPath(path);
    getFileTree(checkedPath)
        .setAttribute(checkedPath, attribute, value, LinkHandling.fromOptions(options));
  }
}
