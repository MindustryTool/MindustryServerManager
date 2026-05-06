package server.utils;

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
        if (path.contains("..") || path.contains("./")) {
            throw new ApiError(400, "Invalid file path");
        }

        Fi baseFile = new Fi(basePath);
        String relative = path.replace(baseFile.absolutePath(), "");
        Fi newFile = baseFile.child(relative);

        if (!Path.of(newFile.absolutePath()).normalize().startsWith(Path.of(baseFile.absolutePath()).normalize())) {
            throw new ApiError(403,
                    "Path is not in server folder: " + relative + ":" + newFile.absolutePath());
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
            throw new ApiError(500, "Error writing file: " + file.absolutePath());
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
