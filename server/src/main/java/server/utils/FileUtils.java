package server.utils;

import java.nio.file.Path;
import java.util.List;

import org.springframework.http.HttpStatus;
import arc.files.Fi;
import dto.ServerFileDto;
import server.config.Const;

public class FileUtils {

    public static Fi getFile(Fi file, String path) {
        return getFile(file.absolutePath(), path);
    }

    public static Fi getFile(String basePath, String path) {
        if (path.contains("..") || path.contains("./")) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "Invalid file path");
        }

        return new Fi(Path.of(basePath, path).toFile());
    }

    public static List<ServerFileDto> getFiles(String path) {
        var file = new Fi(path);

        if (file.length() > Const.MAX_FILE_SIZE) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "File size exceeds max limit");
        }

        if (file.isDirectory()) {
            return file.seq()
                    .map(child -> new ServerFileDto()//
                            .path(child.absolutePath())//
                            .size(child.length())//
                            .directory(child.isDirectory()))//
                    .list();
        }

        return List.of(new ServerFileDto()//
                .path(file.absolutePath())//
                .directory(file.isDirectory())//
                .size(file.length())//
                .data(file.readString()));
    }

    public static void writeFile(String path, byte[] data) {
        var file = new Fi(path);

        if (file.exists()) {
            deleteFile(file);
        }

        file.writeBytes(data);
    }

    public static boolean deleteFile(String path) {
        var file = new Fi(path);

        return deleteFile(file);
    }

    public static boolean deleteFile(Fi file) {
        if (file.isDirectory()) {
            for (Fi child : file.list()) {
                deleteFile(child);
            }
        }
        return file.delete();
    }
}
