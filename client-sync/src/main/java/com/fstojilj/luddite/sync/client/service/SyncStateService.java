package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.SyncStateRepository;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
@Service
public class SyncStateService {

    private final SyncStateRepository syncStateRepository;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDirPath;

    public void removeStaleDirs(List<String> staleDirs) {
        staleDirs.forEach(dir -> {
            var dirPath = Path.of(mirrorDirPath, dir);
            try {
                Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            removeDirectory(dir);
        });

    }

    public List<String> retrieveAllInSyncDirs() {
        return syncStateRepository.findAll().stream()
                .map(SyncHandshakeEntry::dirName)
                .toList();
    }

    public void removeDirectory(String directory) {
        syncStateRepository.remove(directory);
    }

    public List<SyncHandshakeEntry> findAll() {
        return syncStateRepository.findAll();
    }

    public void resetSyncVersionForDir(String dirName) {
        syncStateRepository.reset(dirName);
    }

    public void registerIfAbsent(String dirName) {
        syncStateRepository.registerIfAbsent(dirName);
    }

    public void updateSyncVersion(String dirName, long syncVersion) {
        syncStateRepository.updateSyncVersion(dirName, syncVersion);
    }
}
