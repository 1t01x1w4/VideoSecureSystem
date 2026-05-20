package com.vsec.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public interface StorageService {

    void store(String key, Path localFile) throws IOException;

    InputStream openStream(String key) throws IOException;

    InputStream openStream(String key, long offset, long length) throws IOException;

    void delete(String key) throws IOException;

    boolean exists(String key);

    long size(String key) throws IOException;
}
