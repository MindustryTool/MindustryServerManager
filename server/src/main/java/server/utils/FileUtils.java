package server.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import arc.files.Fi;
import arc.util.ArcRuntimeException;
import arc.util.Log;
import dto.ServerFileDto;
import server.config.Const;

public class FileUtils {

    public static Fi getFile(Fi file, String path) {
        return getFile(file.absolutePath(), path);
    }

    public static Fi getFile(String basePath, String path) {
        if (path == null) {
            throw new ApiError(400, "Invalid file path: path cannot be null");
        }

        if (path.contains("..") || path.contains("./") || path.contains(".\\")) {
            throw new ApiError(400, "Invalid file path");
        }

        Fi baseFile = new Fi(basePath);
        String relative = path.replace(baseFile.absolutePath(), "");
        while (relative.startsWith("/") || relative.startsWith("\\")) {
            relative = relative.substring(1);
        }
        Fi newFile = baseFile.child(relative);

        Path basePathObj = Path.of(baseFile.absolutePath()).toAbsolutePath().normalize();
        Path realBase = basePathObj;
        try {
            if (Files.exists(basePathObj)) {
                realBase = basePathObj.toRealPath();
            }
        } catch (IOException ignored) {
        }

        Path newPath = Path.of(newFile.absolutePath()).toAbsolutePath().normalize();

        if (!newPath.startsWith(basePathObj) && !newPath.startsWith(realBase)) {
            throw new ApiError(403,
                    "Path is not in server folder: " + relative + ":" + newFile.absolutePath());
        }

        Path current = newPath;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current != null) {
            try {
                Path realCurrent = current.toRealPath();
                if (!realCurrent.startsWith(realBase) && !realCurrent.startsWith(basePathObj)) {
                    throw new ApiError(403, "Symlink target is outside server folder: " + realCurrent);
                }
            } catch (IOException e) {
                throw new ApiError(403, "Failed to resolve real path: " + current);
            }
        }

        return newFile;
    }

    public static Object getFiles(String path) {
        var file = new Fi(path);

        if (file.length() > Const.MAX_FILE_SIZE) {
            throw new ApiError(400, "File size exceeds max limit");
        }

        if (file.isDirectory()) {
            return file.seq()
                    .map(child -> new ServerFileDto()
                            .path(toRelativeToServer(child.absolutePath()))
                            .items(child.isDirectory() && child.file().list() != null ? child.file().list().length : 0)
                            .size(child.length())
                            .directory(child.isDirectory()))
                    .list();
        }

        return file.readBytes();
    }

    public static void writeFile(String path, byte[] data) {
        var file = new Fi(path);
        var parent = file.parent();

        if (!parent.exists()) {
            parent.mkdirs();
        }

        if (file.isDirectory()) {
            throw new ApiError(400, "Path is a directory: " + path);
        }

        if (file.exists()) {
            deleteFile(file);
        }

        if (data.length == 0) {
            try {
                file.file().createNewFile();
            } catch (Exception e) {
                Log.err(e.getMessage());
            }
            return;
        }

        try {
            file.writeBytes(data);
        } catch (ArcRuntimeException e) {
            throw new ApiError(500, "Error writing file: [" + file.absolutePath() + "]");
        }
    }

    public static boolean deleteFile(String path) {
        var file = new Fi(path);
        return deleteFile(file);
    }

    public static boolean deleteFile(Fi file) {
        if (!file.exists()) {
            return false;
        }

        if (file.isDirectory()) {
            for (Fi child : file.list()) {
                deleteFile(child);
            }
        }
        return file.delete();
    }

    public static String toRelativeToServer(String path) {
        String config = "config";
        int index = path.indexOf(config);
        if (index == -1) {
            return path;
        }
        return path.substring(index + config.length());
    }
}
