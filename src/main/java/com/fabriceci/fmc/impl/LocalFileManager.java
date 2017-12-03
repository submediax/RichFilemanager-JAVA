package com.fabriceci.fmc.impl;

import com.fabriceci.fmc.AbstractFileManager;
import com.fabriceci.fmc.error.ClientErrorMessage;
import com.fabriceci.fmc.error.FMInitializationException;
import com.fabriceci.fmc.error.FileManagerException;
import com.fabriceci.fmc.model.FileAttributes;
import com.fabriceci.fmc.model.FileData;
import com.fabriceci.fmc.model.FileType;
import com.fabriceci.fmc.util.*;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.fabriceci.fmc.util.FileUtils.getExtension;

public class LocalFileManager extends AbstractFileManager {

    private File docRoot;

    public LocalFileManager() throws FMInitializationException {
        this(null);
    }

    public LocalFileManager(Map<String, String> options) throws FMInitializationException {
        super(options);

        String serverRoot = propertiesConfig.getProperty("serverRoot", "");
        String fileRoot = propertiesConfig.getProperty("fileRoot", "");

        if (!serverRoot.isEmpty() && serverRoot.endsWith("/")) {
            serverRoot = serverRoot.substring(0, serverRoot.length() - 1);
            propertiesConfig.setProperty("serverRoot", serverRoot);
        }
        if (!fileRoot.isEmpty() && fileRoot.endsWith("/")) {
            fileRoot = fileRoot.substring(0, fileRoot.length() - 1);
            propertiesConfig.setProperty("fileRoot", fileRoot);
        }

        docRoot = new File(serverRoot.isEmpty() ? fileRoot : serverRoot + '/' + fileRoot);

        if (docRoot.exists() && docRoot.isFile()) {
            throw new FMInitializationException("File manager root must be a directory !");
        } else if (!docRoot.exists()) {
            try {
                Files.createDirectory(docRoot.toPath());
            } catch (IOException e) {
                throw new FMInitializationException("Unable the create the doc root directory: " + docRoot.getAbsolutePath(), e);
            }
        }
    }

    @Override
    public List<FileData> actionReadFolder(String path, String type) throws FileManagerException {

        File dir = getFile(path);

        checkPath(dir);
        checkReadPermission(dir);

        if (!dir.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.DIRECTORY_NOT_EXIST, Collections.singletonList(path));
        }

        checkRestrictions(dir);

        String[] files;
        try {
            files = dir.list();
        } catch (SecurityException e) {
            throw new FileManagerException(ClientErrorMessage.UNABLE_TO_OPEN_DIRECTORY, Collections.singletonList(path));
        }

        String filePath;
        File file;

        List<FileData> fileDataList = new ArrayList<>();
        if (files != null) {
            for (String f : files) {

                file = new File(docRoot.getPath() + path + f);
                filePath = path + f;
                String filename = file.getName();

                if (file.isDirectory()) {
                    if (isAllowedPattern(filename, file.isDirectory())) {
                        fileDataList.add(getFileInfo(filePath + "/"));
                    }
                } else if (isAllowedPattern(filename, file.isDirectory())) {
                    if (type == null || type.equals("images") && isAllowedImageExt(getExtension(filename))) {
                        fileDataList.add(getFileInfo(filePath));
                    }
                }
            }
        }

        return fileDataList;
    }

    @Override
    public FileData actionGetInfo(String path) throws FileManagerException {

        File file = new File(docRoot.getPath() + path);

        if (file.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        checkPath(file);
        checkReadPermission(file);
        checkRestrictions(file);

        // check if file is readable
        if (!file.canRead()) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED_SYSTEM);
        }

        return getFileInfo(path);

    }

    public FileData getFileInfo(String path) throws FileManagerException {

        FileData fileData = new FileData();
        fileData.setId(path);
        FileAttributes fileAttributes = new FileAttributes();
        // get file
        File file = getFile(path);

        if (file.isDirectory() && !path.endsWith("/")) {
            throw new FileManagerException("Error reading the file: " + file.getAbsolutePath());
        }

        BasicFileAttributes attr;
        try {
            attr = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        } catch (IOException e) {
            throw new FileManagerException("Error reading the file: " + file.getAbsolutePath(), e);
        }

        fileAttributes.setReadable(file.canRead() ? 1 : 0);
        fileAttributes.setWritable(file.canWrite() ? 1 : 0);

        String filename = file.getName();
        if (file.isDirectory()) {
            fileData.setType(FileType.folder);
        } else {
            fileData.setType(FileType.file);
            Dimension dim = new Dimension(0, 0);
            if (fileAttributes.isReadable()) {
                fileAttributes.setSize(file.length());
                if (isAllowedImageExt(getExtension(filename))) {
                    if (file.length() > 0) {
                        dim = ImageUtils.getImageSize(docRoot.getPath() + path);
                    }
                }
            }
            fileAttributes.setWidth(dim.getWidth());
            fileAttributes.setHeight(dim.getHeight());
        }

        fileAttributes.setName(filename);
        fileAttributes.setPath(getDynamicPath(path));
        fileAttributes.setTimestamp(attr.lastModifiedTime().toMillis());
        fileAttributes.setModified(df.format(new Date(attr.lastModifiedTime().toMillis())));
        fileAttributes.setCreated(df.format(new Date(attr.creationTime().toMillis())));

        fileData.setAttributes(fileAttributes);

        return fileData;
    }

    @Override
    public FileData actionAddFolder(String path, String name) throws FileManagerException {

        String filename = normalizeName(name);
        File parentFile = new File(docRoot.getPath() + path);
        File targetFolderFile = new File(docRoot.getPath() + path + filename);

        checkPath(parentFile);
        checkWritePermission(parentFile);
        checkRestrictions(targetFolderFile);

        if (filename.length() == 0) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_NAME, Collections.singletonList(name));
        }

        if (targetFolderFile.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.DIRECTORY_ALREADY_EXISTS, Collections.singletonList(path + filename));
        }
        try {
            Files.createDirectories(targetFolderFile.toPath());
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.UNABLE_TO_CREATE_DIRECTORY, Collections.singletonList(path + filename));
        }

        return getFileInfo(path + filename + "/");
    }

    @Override
    public FileData actionMove(String sourcePath, String targetPath) throws FileManagerException {

        File sourceFile = getFile(sourcePath);
        String filename = sourceFile.getName();
        File targetDir = getFile(targetPath);
        File targetFile = getFile(targetPath + "/" + filename);

        String finalTargetPath = targetPath + filename + (sourceFile.isDirectory() ? "/" : "");

        if (!targetDir.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.DIRECTORY_NOT_EXIST, Collections.singletonList(targetPath));
        }

        // check if not requesting main FM userfiles folder
        if (sourceFile.equals(docRoot)) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
        }

        // check permissions
        checkPath(sourceFile);
        checkPath(targetDir);
        checkReadPermission(sourceFile);
        checkWritePermission(sourceFile);
        checkWritePermission(targetDir);
        checkRestrictions(sourceFile);
        checkRestrictions(targetFile);

        // check if file already exists
        if (targetFile.exists()) {
            if (targetFile.isDirectory()) {
                throw new FileManagerException(ClientErrorMessage.DIRECTORY_ALREADY_EXISTS, Collections.singletonList(targetPath));
            } else {
                throw new FileManagerException(ClientErrorMessage.FILE_ALREADY_EXISTS, Collections.singletonList(targetPath));
            }
        }

        try {

            Files.move(sourceFile.toPath(), targetFile.toPath());
            File thumbnailFile = new File(getThumbnailPath(sourcePath));
            if (thumbnailFile.exists()) {
                if (thumbnailFile.isFile()) {
                    File newThumbnailFile = new File(getThumbnailPath(targetPath + filename));
                    Files.createDirectories(newThumbnailFile.getParentFile().toPath());
                    Files.move(thumbnailFile.toPath(), newThumbnailFile.toPath());

                } else {
                    FileUtils.removeDirectory(thumbnailFile.toPath());
                }
            }

        } catch (IOException e) {
            if (sourceFile.isDirectory()) {
                throw new FileManagerException("ERROR_MOVING_DIRECTORY", Collections.singletonList(targetPath));
            } else {
                throw new FileManagerException("ERROR_MOVING_FILE", Collections.singletonList(targetPath));
            }

        }

        return getFileInfo(finalTargetPath);
    }


    @Override
    public FileData actionDelete(String path) throws FileManagerException {

        File thumbnail = new File(getThumbnailPath(path));
        File file = new File(docRoot.getPath() + path);

        checkPath(file);
        checkWritePermission(file);
        checkRestrictions(file);

        // check if not requesting main FM userfiles folder
        if (file.equals(docRoot)) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
        }

        // Recover the result before the operation
        FileData result = getFileInfo(path);

        if (file.isDirectory()) {
            try {
                FileUtils.removeDirectory(file.toPath());
                if (thumbnail.exists()) {
                    FileUtils.removeDirectory(thumbnail.toPath());
                }
            } catch (IOException e) {
                logger.error("Cannot remove directory : " + path);
                throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
            }
        } else {
            if (!file.delete()) {
                throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
            }
            if (thumbnail.exists()) {
                thumbnail.delete();
            }
        }
        return result;
    }

    @Override
    public FileData actionGetImage(HttpServletResponse response, String path, Boolean thumbnail) throws FileManagerException {
        InputStream is;
        File file = getFile(path);

        checkPath(file);
        checkReadPermission(file);
        checkRestrictions(file);

        if (file.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        checkRestrictions(file);

        try {
            String filename = file.getName();
            String fileExt = filename.substring(filename.lastIndexOf(".") + 1);
            String mimeType = (!StringUtils.isEmpty(getExtension(fileExt))) ? FileManagerUtils.getMimeTypeByExt(fileExt) : "application/octet-stream";
            long fileSize = file.length();
            if (thumbnail) {

                if (Boolean.parseBoolean(propertiesConfig.getProperty("images.thumbnail.enabled"))) {

                    File thumbnailFile = getThumbnail(path, true);
                    if (thumbnailFile == null) throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
                    is = new FileInputStream(thumbnailFile);
                    fileSize = thumbnailFile.length();
                } else {
                    // no cache
                    BufferedImage image = ImageIO.read(file);
                    BufferedImage resizedImage = generateThumbnail(image);
                    ByteArrayOutputStream os = new ByteArrayOutputStream();
                    ImageIO.write(resizedImage, fileExt, os);
                    is = new ByteArrayInputStream(os.toByteArray());
                    fileSize = os.toByteArray().length;
                }

            } else {
                is = new FileInputStream(file);
            }

            response.setContentType(mimeType);
            response.setHeader("Content-Length", Long.toString(fileSize));
            response.setHeader("Content-Transfer-Encoding", "binary");
            response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");

            FileUtils.copy(new BufferedInputStream(is), response.getOutputStream());
        } catch (IOException e) {
            logger.error("Error serving image: " + file.getName(), e);
            throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
        }
        return null;
    }


    /**
     * NEED TEST
     */

    @Override
    public FileData actionRename(String sourcePath, String targetName) throws FileManagerException {

        File sourceFile = getFile(sourcePath);

        int pathPos = sourcePath.lastIndexOf("/");
        String targetDirPath = sourcePath.substring(0, pathPos + 1);
        File targetDir = new File(targetDirPath);

        String targetPath = targetDirPath + targetName;

        File targetFile = new File(targetPath);

        // forbid to change path during rename
        if (targetName.contains("/")) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_CHAR_SLASH);
        }

        // check if not requesting main FM userfiles folder
        if (sourceFile.equals(docRoot)) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
        }

        // check permissions
        checkPath(sourceFile);
        checkReadPermission(sourceFile);
        checkWritePermission(sourceFile);
        checkWritePermission(targetDir);
        checkRestrictions(sourceFile);
        checkRestrictions(targetFile);


        if (!sourceFile.renameTo(targetFile)) {
            if (sourceFile.isDirectory()) {
                throw new FileManagerException(ClientErrorMessage.ERROR_RENAMING_DIRECTORY, Arrays.asList(sourcePath, targetPath));
            } else {
                throw new FileManagerException(ClientErrorMessage.ERROR_RENAMING_FILE, Arrays.asList(sourcePath, targetPath));
            }
        }

        File oldThumbnailFile = new File(getThumbnailPath(sourcePath));
        if (oldThumbnailFile.exists()) {
            oldThumbnailFile.renameTo(new File(getThumbnailPath(targetPath)));
        }

        return getFileInfo(targetPath);

    }


    @Override
    public FileData actionReadFile(HttpServletResponse response, String path) throws FileManagerException {

        File file = getFile(path);

        checkPath(file);
        checkReadPermission(file);
        checkRestrictions(file);

        if (file.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        String filename = file.getName();
        String fileExt = filename.substring(filename.lastIndexOf(".") + 1);
        String mimeType = FileManagerUtils.getMimeTypeByExt(fileExt);
        long fileSize = file.length();

        //TO DO : IMPLEMENT HTTP RANGE FOR STREAM FILE (AUDIO/VIDEO)

        response.setContentType(mimeType);
        response.setHeader("Content-Length", Long.toString(fileSize));
        response.setHeader("Content-Transfer-Encoding", "binary");
        response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");

        try {
            FileUtils.copy(new BufferedInputStream(new FileInputStream(file)), response.getOutputStream());
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
        }
        return null;
    }


    @Override
    public FileData actionSummarize() throws FileManagerException {
        FileAttributes attributes;
        try {
            attributes = getDirSummary(getFile("/").toPath());
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
        }

        FileData fileData = new FileData();
        fileData.setId("/");
        fileData.setType(FileType.summary);
        fileData.setAttributes(attributes);

        return fileData;

    }

    @Override
    public FileData actionDownload(HttpServletResponse response, String path) throws FileManagerException {


        File file = getFile(path);
        String filename = file.getName();

        checkPath(file);
        checkReadPermission(file);
        checkRestrictions(file);

        // check if not requesting main FM userfiles folder
        if (file.equals(docRoot)) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
        }

        if (file.isDirectory()) {

            // check  if permission is granted
            if (!Boolean.parseBoolean(propertiesConfig.getProperty("allowFolderDownload"))) {
                throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
            }
        }

        try {
            response.setHeader("Content-Description", "File Transfer");
            // handle cache
            response.setHeader("'Pragma: public'", "public");
            response.setHeader("Expires", "0");
            response.setHeader("Cache-Control", "must-revalidate, post-check=0, pre-check=0");

            if (file.isFile()) {
                String fileExt = filename.substring(filename.lastIndexOf(".") + 1);
                String mimeType = (!StringUtils.isEmpty(FileManagerUtils.mimetypes.get(fileExt))) ? FileManagerUtils.mimetypes.get(fileExt) : "application/octet-stream";
                response.setContentLength((int) file.length());
                response.setContentType(mimeType);
                response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                response.setContentLength((int) file.length());

                FileUtils.copy(new BufferedInputStream(new FileInputStream(file)), response.getOutputStream());
            } else {
                String[] files = file.list();

                if (files == null || files.length == 0) {
                    throw new FileManagerException(ClientErrorMessage.DIRECTORY_EMPTY);
                }

                String zipFileName = FileUtils.getBaseName(path.substring(0, path.length() - 1)) + ".zip";
                String mimType = FileManagerUtils.mimetypes.get("zip");
                response.setContentType(mimType);
                response.setHeader("Content-Disposition", "attachment; filename=\"" + zipFileName + "\"");
                byte[] zipFileByteArray;
                try {
                    zipFileByteArray = ZipUtils.zipFolder(file);
                } catch (IOException e) {
                    throw new FileManagerException(ClientErrorMessage.ERROR_CREATING_ZIP);
                }
                response.setContentLength(zipFileByteArray.length);

                FileUtils.copy(new ByteArrayInputStream(zipFileByteArray), response.getOutputStream());
            }

        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
        }

        return null;

    }


    @Override
    public List<FileData> actionUpload(HttpServletRequest request, String path) throws FileManagerException {

        File targetDirectory = getFile(path);
        String targetDirectoryString = path.substring(0, path.lastIndexOf("/") + 1);

        checkPath(targetDirectory);
        checkWritePermission(targetDirectory);

        return uploadFiles(request, targetDirectoryString);

    }

    private ArrayList<FileData>  uploadFiles(HttpServletRequest request, String targetDirectory) throws FileManagerException {
        ArrayList<FileData> array = new ArrayList<>();
        try {
            for (Part uploadedFile : request.getParts()) {

                if (uploadedFile.getContentType() == null) {
                    continue;
                }

                if (uploadedFile.getSize() == 0) {
                    throw new FileManagerException(ClientErrorMessage.FILE_EMPTY);
                }

                String submittedFileName = uploadedFile.getSubmittedFileName();
                String filename = normalizeName(FileUtils.getBaseName(submittedFileName)) + '.' + FileUtils.getExtension(submittedFileName);

                checkRestrictions(new File(targetDirectory + "/" + filename));
                Long uploadFileSizeLimit = 0L;
                String uploadFileSizeLimitString = propertiesConfig.getProperty("upload.fileSizeLimit");
                try {
                    uploadFileSizeLimit = Long.parseLong(uploadFileSizeLimitString);
                } catch (NumberFormatException e) {
                    logger.error("Wrong format for the property: 'upload.fileSizeLimit");
                    throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
                }

                if (uploadedFile.getSize() > uploadFileSizeLimit) {
                    throw new FileManagerException(ClientErrorMessage.UPLOAD_FILES_SMALLER_THAN, Collections.singletonList(String.valueOf(FileUtils.humanReadableByteCount(uploadFileSizeLimit, true))));
                }

                String uploadedPath = getFile(targetDirectory).getAbsolutePath() + "/" + filename;

                Files.copy(new BufferedInputStream(uploadedFile.getInputStream()), Paths.get(uploadedPath), StandardCopyOption.REPLACE_EXISTING);
                array.add(getFileInfo(targetDirectory + filename));
            }
        } catch (IOException|ServletException e){
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }
        return array;
    }

    @Override
    public FileData actionSaveFile(String pathParam, String contentParam) throws FileManagerException {

        File file = getFile(pathParam);

        checkPath(file);
        checkWritePermission(file);
        checkRestrictions(file);

        if (file.isDirectory()) {
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        try {
            FileOutputStream oldFile = new FileOutputStream(file, false);
            oldFile.write(contentParam.getBytes());
            oldFile.close();
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_SAVING_FILE);
        }

        return getFileInfo(pathParam);
    }


    @Override
    public FileData actionCopy(String sourcePath, String targetDirPath) throws FileManagerException {

        File sourceFile = getFile(sourcePath);
        String filename = sourceFile.getName();
        File targetDir = getFile(targetDirPath);
        File targetFile = getFile(targetDirPath + filename);

        String finalPath = targetDirPath + filename + (sourceFile.isDirectory() ? "/" : "");

        if(!targetDir.isDirectory()){
            throw new FileManagerException(ClientErrorMessage.DIRECTORY_NOT_EXIST, Collections.singletonList(targetDirPath));
        }

        if (sourceFile.equals(docRoot)) {
            throw new FileManagerException(ClientErrorMessage.NOT_ALLOWED);
        }

        checkPath(sourceFile);
        checkReadPermission(sourceFile);
        checkRestrictions(sourceFile);

        checkPath(targetDir, true);
        checkReadPermission(targetDir);
        checkRestrictions(targetDir);

        // check if file already exists
        if (targetFile.exists()) {
            if (targetFile.isDirectory()) {
                throw new FileManagerException(ClientErrorMessage.DIRECTORY_ALREADY_EXISTS, Collections.singletonList(finalPath));
            } else {
                throw new FileManagerException(ClientErrorMessage.FILE_ALREADY_EXISTS, Collections.singletonList(finalPath));
            }
        }

        try {
            if (sourceFile.isDirectory()) {
                FileUtils.copyDirectory(sourceFile.toPath(), targetFile.toPath());
            } else {
                // TO DO : copy thumbnail
                Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException e) {
            if (sourceFile.isDirectory()) {
                throw new FileManagerException(ClientErrorMessage.ERROR_COPYING_DIRECTORY, Collections.singletonList(finalPath));
            } else {
                throw new FileManagerException(ClientErrorMessage.ERROR_COPYING_FILE, Collections.singletonList(finalPath));
            }

        }

        return getFileInfo(finalPath);
    }

    @Override
    public List<FileData> actionExtract(String sourcePath, String targetPath) throws FileManagerException {

        File sourceFile = getFile(sourcePath);
        File targetDirFile = getFile(targetPath);

        if(FileUtils.getExtension(sourceFile.getName()).toLowerCase().equals("zip")){
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        if(sourceFile.isDirectory()){
            throw new FileManagerException(ClientErrorMessage.FORBIDDEN_ACTION_DIR);
        }

        checkPath(sourceFile);
        checkPath(targetDirFile, true);
        checkRestrictions(sourceFile);
        checkWritePermission(targetDirFile);
        checkRestrictions(sourceFile);
        checkRestrictions(targetDirFile);

        List<FileData> fileDataList = new ArrayList<>();

        try {

            byte[] buffer = new byte[1024];
            ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceFile));
            ZipEntry zipEntry = zis.getNextEntry();
            while(zipEntry != null){
                String fileName = zipEntry.getName();
                File newFile = new File(targetDirFile.getAbsolutePath() + "/" + fileName);
                if(isMatchRestriction(newFile)){
                    fileDataList.add(getFileInfo(targetPath + newFile.getName()));
                    FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                    fos.close();
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
            zis.close();
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_CREATING_ZIP);
        }

        return fileDataList;
    }

    @Override
    public Object actionSeekFolder(String folderPath, String term) throws FileManagerException {

        File file = getFile(folderPath);

        checkPath(file);
        checkWritePermission(file);
        checkReadPermission(file);
        checkRestrictions(file);

        List<FileData> fileDataList = new ArrayList();

        try {
            Files.walkFileTree(file.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {

                        File currentFile = file.toFile();
                        if (isMatchRestriction(currentFile) && currentFile.getName().toLowerCase().startsWith(term)) {
                            fileDataList.add(getFileInfo(getRelativePath(currentFile)));
                        }

                    } catch (FileManagerException silent) {} finally {
                        return FileVisitResult.CONTINUE;
                    }
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        return FileVisitResult.CONTINUE;
                    } else {
                        throw exc;
                    }
                }
            });
        } catch (IOException e) {
            throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
        }

        return fileDataList;
    }

    private static FileAttributes getDirSummary(Path path) throws IOException {

        FileAttributes fileAttributes = new FileAttributes();

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                fileAttributes.setFiles(fileAttributes.getFiles() + 1);
                fileAttributes.setSize(fileAttributes.getSize() + Files.size(file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                fileAttributes.setFiles(fileAttributes.getFiles() + 1);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc == null) {
                    fileAttributes.setFolders(fileAttributes.getFolders() + 1);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw exc;
                }
            }
        });


        return fileAttributes;
    }

    private String getDynamicPath(String path) {
        String fileRoot = propertiesConfig.getProperty("fileRoot");
        if (fileRoot.isEmpty()) return path;

        return fileRoot + path;
    }

    private File getFile(String path) {
        return new File(docRoot.getPath() + path);
    }

    protected String getRelativePath(File file){
        return file.getAbsolutePath().substring(docRoot.getAbsolutePath().length());
    }

    protected String getThumbnailPath(String path) throws FileManagerException {
        return getThumbnailDir().getPath() + path;
    }

    protected File getThumbnailDir() throws FileManagerException {

        final String serverRoot = propertiesConfig.getProperty("serverRoot", "");
        final String thumbnailDirPath = propertiesConfig.getProperty("images.thumbnail.dir");
        File thumbnailDirFile = new File(StringUtils.isEmpty(serverRoot) ? thumbnailDirPath : serverRoot + '/' + thumbnailDirPath);

        if (!thumbnailDirFile.exists()) {
            try {
                Files.createDirectory(thumbnailDirFile.toPath());
            } catch (IOException e) {
                logger.error("Could not create the thumbnail directory: " + thumbnailDirFile.getAbsolutePath(), e);
                throw new FileManagerException(ClientErrorMessage.ERROR_SERVER);
            }
        }

        return thumbnailDirFile;
    }

    protected File getThumbnail(String path, boolean create) throws FileManagerException, IOException {

        File thumbnailFile = new File(getThumbnailPath(path));

        if (thumbnailFile.exists()) {
            return thumbnailFile;
        } else if (!create) {
            return null;
        }

        File originalFile = new File(docRoot.getPath() + path);
        String ext = FileUtils.getExtension(originalFile.getName());

        if (!originalFile.exists()) {
            throw new FileManagerException(ClientErrorMessage.FILE_DOES_NOT_EXIST, Collections.singletonList(path));
        }

        try {
            Files.createDirectories(thumbnailFile.getParentFile().toPath());

            BufferedImage source = ImageIO.read(originalFile);
            BufferedImage resizedImage = generateThumbnail(source);
            ImageIO.write(resizedImage, ext, thumbnailFile);
        } catch (IOException e) {
            logger.error("Error during thumbnail generation - ext: " + ext + " name: " + originalFile.getName(), e);
            return null;
        }

        return thumbnailFile;
    }

}