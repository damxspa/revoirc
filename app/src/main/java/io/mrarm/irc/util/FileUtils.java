package io.mrarm.irc.util;

import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for safe file operations with proper null handling.
 */
public final class FileUtils {

    private FileUtils() {}

    /**
     * Safely lists files in a directory, returning an empty list if the directory
     * doesn't exist, isn't a directory, or an I/O error occurs.
     * 
     * @param dir Directory to list
     * @return List of files (never null, but may be empty)
     */
    public static List<File> listFiles(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w("FileUtils", "listFiles returned null for directory: " + dir.getAbsolutePath());
            return new ArrayList<>();
        }
        // Return mutable ArrayList instead of fixed-size Arrays.asList
        return new ArrayList<>(Arrays.asList(files));
    }

    /**
     * Safely lists files in a directory with a name filter, returning an empty list if the directory
     * doesn't exist, isn't a directory, or an I/O error occurs.
     * 
     * @param dir Directory to list
     * @param nameFilter Filter to apply to file names
     * @return List of matching files (never null, but may be empty)
     */
    public static List<File> listFiles(File dir, FilenameFilter nameFilter) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new ArrayList<>();
        }
        File[] files = dir.listFiles(nameFilter);
        if (files == null) {
            Log.w("FileUtils", "listFiles returned null for directory: " + dir.getAbsolutePath());
            return new ArrayList<>();
        }
        // Return mutable ArrayList instead of fixed-size Arrays.asList
        return new ArrayList<>(Arrays.asList(files));
    }

    /**
     * Recursively deletes a file or directory.
     * @param file File or directory to delete
     * @return true if deletion succeeded completely, false if any deletion failed
     */
    public static boolean deleteRecursive(File file) {
        if (file == null) return true;
        boolean success = true;
        if (file.isDirectory()) {
            List<File> children = listFiles(file);
            for (File child : children) {
                if (!deleteRecursive(child)) {
                    success = false;
                    Log.w("FileUtils", "Failed to delete child: " + child.getAbsolutePath());
                }
            }
        }
        if (!file.delete()) {
            success = false;
            Log.w("FileUtils", "Failed to delete: " + file.getAbsolutePath());
        }
        return success;
    }

    /**
     * Copies a single file.
     * @param source Source file
     * @param dest Destination file
     * @return true if copy succeeded, false otherwise
     */
    public static boolean copyFile(File source, File dest) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
            byte[] buffer = new byte[16 * 1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            return true;
        } catch (java.io.IOException e) {
            Log.e("FileUtils", "Failed to copy file " + source.getName() + " to " + dest.getName(), e);
            return false;
        }
    }

    /**
     * Copies a directory recursively.
     * @param source Source directory
     * @param dest Destination directory
     * @return true if copy succeeded, false otherwise
     */
    public static boolean copyDirectory(File source, File dest) {
        if (source == null || !source.exists() || !source.isDirectory()) return false;
        if (!dest.exists() && !dest.mkdirs()) return false;
        if (!dest.isDirectory()) return false;

        List<File> files = listFiles(source);
        for (File file : files) {
            File destFile = new File(dest, file.getName());
            if (file.isDirectory()) {
                if (!copyDirectory(file, destFile)) return false;
            } else {
                if (!copyFile(file, destFile)) return false;
            }
        }
        return true;
    }
}