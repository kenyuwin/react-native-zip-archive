package com.rnziparchive;

import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import java.nio.charset.Charset;

public class RNZipArchiveModule extends ReactContextBaseJavaModule {
  private static final String TAG = RNZipArchiveModule.class.getSimpleName();

  private static final String PROGRESS_EVENT_NAME = "zipArchiveProgressEvent";
  private static final String EVENT_KEY_FILENAME = "filePath";
  private static final String EVENT_KEY_PROGRESS = "progress";

  public RNZipArchiveModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return "RNZipArchive";
  }

  @ReactMethod
  public void isPasswordProtected(final String zipFilePath, final Promise promise) {
    try {
      net.lingala.zip4j.core.ZipFile zipFile = new net.lingala.zip4j.core.ZipFile(zipFilePath);
      promise.resolve(zipFile.isEncrypted());
    } catch (ZipException ex) {
      promise.reject(null, String.format("Unable to check for encryption due to: %s", getStackTrace(ex)));
    }
  }

  @ReactMethod
  public void unzipWithPassword(final String zipFilePath, final String destDirectory,
        final String password, final Promise promise) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          net.lingala.zip4j.core.ZipFile zipFile = new net.lingala.zip4j.core.ZipFile(zipFilePath);
          if (zipFile.isEncrypted()) {
            zipFile.setPassword(password);
          } else {
            promise.reject(null, String.format("Zip file: %s is not password protected", zipFilePath));
          }

          List fileHeaderList = zipFile.getFileHeaders();
          List extractedFileNames = new ArrayList<>();
          int totalFiles = fileHeaderList.size();

          updateProgress(0, 1, zipFilePath); // force 0%
          for (int i = 0; i < totalFiles; i++) {
            FileHeader fileHeader = (FileHeader) fileHeaderList.get(i);

            File fout = new File(destDirectory, fileHeader.getFileName());
            ensureZipPathSafety(fout, destDirectory);

            zipFile.extractFile(fileHeader, destDirectory);
            if (!fileHeader.isDirectory()) {
              extractedFileNames.add(fileHeader.getFileName());
            }
            updateProgress(i + 1, totalFiles, zipFilePath);
          }
          promise.resolve(Arguments.fromList(extractedFileNames));
        } catch (Exception ex) {
          updateProgress(0, 1, zipFilePath); // force 0%
          promise.reject(null, String.format("Failed to unzip file, due to: %s", getStackTrace(ex)));
        }
      }
    }).start();
  }

  @ReactMethod
  public void unzip(final String zipFilePath, final String destDirectory, final String charset, final Promise promise) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        // Check the file exists
        FileInputStream inputStream = null;
        try {
          inputStream = new FileInputStream(zipFilePath);
          new File(zipFilePath);
        } catch (FileNotFoundException | NullPointerException e) {
          if (inputStream != null) {
            try {
              inputStream.close();
            } catch (IOException ignored) {
            }
          }
          promise.reject(null, "Couldn't open file " + zipFilePath + ". ");
          return;
        }

        try {
          // Find the total uncompressed size of every file in the zip, so we can
          // get an accurate progress measurement
          final long totalUncompressedBytes = getUncompressedSize(zipFilePath, charset);

          File destDir = new File(destDirectory);
          if (!destDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
          }

          updateProgress(0, 1, zipFilePath); // force 0%

          // We use arrays here so we can update values
          // from inside the callback
          final long[] extractedBytes = {0};
          final int[] lastPercentage = {0};

          final ZipFile zipFile = new ZipFile(zipFilePath, Charset.forName(charset));
          final Enumeration<? extends ZipEntry> entries = zipFile.entries();
          Log.d(TAG, "Zip has " + zipFile.size() + " entries");
          while (entries.hasMoreElements()) {
            final ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;

            StreamUtil.ProgressCallback cb = new StreamUtil.ProgressCallback() {
              @Override
              public void onCopyProgress(long bytesRead) {
                extractedBytes[0] += bytesRead;

                int lastTime = lastPercentage[0];
                int percentDone = (int) ((double) extractedBytes[0] * 100 / (double) totalUncompressedBytes);

                // update at most once per percent.
                if (percentDone > lastTime) {
                  lastPercentage[0] = percentDone;
                  updateProgress(extractedBytes[0], totalUncompressedBytes, zipFilePath);
                }
              }
            };

            File fout = new File(destDirectory, entry.getName());
            ensureZipPathSafety(fout, destDirectory);

            if (!fout.exists()) {
              //noinspection ResultOfMethodCallIgnored
              (new File(fout.getParent())).mkdirs();
            }
            InputStream in = null;
            BufferedOutputStream Bout = null;
            try {
              in = zipFile.getInputStream(entry);
              Bout = new BufferedOutputStream(new FileOutputStream(fout));
              StreamUtil.copy(in, Bout, cb);
              Bout.close();
              in.close();
            } catch (IOException ex) {
              if (in != null) {
                try {
                  in.close();
                } catch (Exception ignored) {
                }
              }
              if (Bout != null) {
                try {
                  Bout.close();
                } catch (Exception ignored) {
                }
              }
            }
          }

          zipFile.close();
          updateProgress(1, 1, zipFilePath); // force 100%
          promise.resolve(destDirectory);
        } catch (Exception ex) {
          updateProgress(0, 1, zipFilePath); // force 0%
          promise.reject(null, "Failed to extract file " + ex.getLocalizedMessage());
        }
      }
    }).start();
  }

  /**
   * Extract a zip held in the assets directory.
   * <p>
   * Note that the progress value isn't as accurate as when unzipping
   * from a file. When reading a zip from a stream, we can't
   * get accurate uncompressed sizes for files (ZipEntry#getCompressedSize() returns -1).
   * <p>
   * Instead, we compare the number of bytes extracted to the size of the compressed zip file.
   * In most cases this means the progress 'stays on' 100% for a little bit (compressedSize < uncompressed size)
   */
  @ReactMethod
  public void unzipAssets(final String assetsPath, final String destDirectory, final Promise promise) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        InputStream assetsInputStream;
        final long size;

        try {
          assetsInputStream = getReactApplicationContext().getAssets().open(assetsPath);
          AssetFileDescriptor fileDescriptor = getReactApplicationContext().getAssets().openFd(assetsPath);
          size = fileDescriptor.getLength();
        } catch (IOException e) {
          promise.reject(null, String.format("Asset file `%s` could not be opened", assetsPath));
          return;
        }

        try {
          try {
            File destDir = new File(destDirectory);
            if (!destDir.exists()) {
              //noinspection ResultOfMethodCallIgnored
              destDir.mkdirs();
            }
            ZipInputStream zipIn = new ZipInputStream(assetsInputStream);
            BufferedInputStream bin = new BufferedInputStream(zipIn);

            ZipEntry entry;

            final long[] extractedBytes = {0};
            final int[] lastPercentage = {0};

            updateProgress(0, 1, assetsPath); // force 0%
            File fout;
            while ((entry = zipIn.getNextEntry()) != null) {
              if (entry.isDirectory()) continue;
              fout = new File(destDirectory, entry.getName());

              ensureZipPathSafety(fout, destDirectory);

              if (!fout.exists()) {
                //noinspection ResultOfMethodCallIgnored
                (new File(fout.getParent())).mkdirs();
              }

              final ZipEntry finalEntry = entry;
              StreamUtil.ProgressCallback cb = new StreamUtil.ProgressCallback() {
                @Override
                public void onCopyProgress(long bytesRead) {
                  extractedBytes[0] += bytesRead;

                  int lastTime = lastPercentage[0];
                  int percentDone = (int) ((double) extractedBytes[0] * 100 / (double) size);

                  // update at most once per percent.
                  if (percentDone > lastTime) {
                    lastPercentage[0] = percentDone;
                    updateProgress(extractedBytes[0], size, finalEntry.getName());
                  }
                }
              };

              FileOutputStream out = new FileOutputStream(fout);
              BufferedOutputStream Bout = new BufferedOutputStream(out);
              StreamUtil.copy(bin, Bout, cb);
              Bout.close();
              out.close();
            }

            updateProgress(1, 1, assetsPath); // force 100%
            bin.close();
            zipIn.close();
          } catch (Exception ex) {
            ex.printStackTrace();
            updateProgress(0, 1, assetsPath); // force 0%
            throw new Exception(String.format("Couldn't extract %s", assetsPath));
          }
        } catch (Exception ex) {
          promise.reject(null, ex.getMessage());
          return;
        }
        promise.resolve(destDirectory);
      }
    }).start();
  }

  @ReactMethod
  public void zip(String fileOrDirectory, String destDirectory, Promise promise) {
    try{

      ZipParameters parameters = new ZipParameters();
      parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
      parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);

      processZip(fileOrDirectory, destDirectory, parameters, promise);

    } catch (Exception ex) {
      promise.reject(null, ex.getMessage());
      return;
    }
  }

  @ReactMethod
  public void zipWithPassword(String fileOrDirectory, String destDirectory, String password,
      String encryptionMethod, Promise promise) {
    try{

      ZipParameters parameters = new ZipParameters();
      parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
      parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);

      String encParts[] = _encryptionMethod.split("-");

      if (password != null && !password.isEmpty()) {
        parameters.setEncryptFiles(true);
        if (encParts[0].equals("AES")) {
          parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
          if (encParts[1].equals("128")) {
            parameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_128);
          } else if (encParts[1].equals("256")) {
            parameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
          } else {
            parameters.setAesKeyStrength(Zip4jConstants.ENC_METHOD_STANDARD);
          }
        } else if (_encryptionMethod.equals("STANDARD")) {
          parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);
          Log.d(TAG, "Standard Encryption");
        } else {
          parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_STANDARD);
          Log.d(TAG, "Encryption type not supported default to Standard Encryption");
        }
        parameters.setPassword(password);
      } else {
        promise.reject(null, "Password is empty");
      }

      processZip(fileOrDirectory, destDirectory, parameters, promise);

    } catch (Exception ex) {
      promise.reject(null, ex.getMessage());
      return;
    }

  }

  private void processZip(final String fileOrDirectory, final String destDirectory, final ZipParameters parameters, final Promise promise) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          net.lingala.zip4j.core.ZipFile zipFile = new net.lingala.zip4j.core.ZipFile(destDirectory);

          updateProgress(0, 100, destDirectory);

          File f = new File(fileOrDirectory);

          int totalFiles = 0;
          int fileCounter = 0;

          if (f.exists()) {
            if (f.isDirectory()) {

              List<File> files = Arrays.asList(f.listFiles());

              totalFiles = files.size();
              for (int i = 0; i < files.size(); i++) {
                if (files.get(i).isDirectory()) {
                  zipFile.addFolder(files.get(i).getAbsolutePath(), parameters);
                }
                else {
                  zipFile.addFile(files.get(i), parameters);
                }
                fileCounter += 1;
                updateProgress(fileCounter, totalFiles, destDirectory);
              }

            } else {
              totalFiles = 1;
              zipFile.addFile(f, parameters);
              fileCounter += 1;
              updateProgress(fileCounter, totalFiles, destDirectory);
            }
          }
          else {
            promise.reject(null, "File or folder does not exist");
          }

          updateProgress(1, 1, destDirectory); // force 100%
          promise.resolve(destDirectory);

        } catch (Exception ex) {
          promise.reject(null, ex.getMessage());
          return;
        }
      }
    }).start();
  }

  protected void updateProgress(long extractedBytes, long totalSize, String zipFilePath) {
    // Ensure progress can't overflow 1
    double progress = Math.min((double) extractedBytes / (double) totalSize, 1);
    Log.d(TAG, String.format("updateProgress: %.0f%%", progress * 100));

    WritableMap map = Arguments.createMap();
    map.putString(EVENT_KEY_FILENAME, zipFilePath);
    map.putDouble(EVENT_KEY_PROGRESS, progress);
    getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(PROGRESS_EVENT_NAME, map);
  }

  /**
   * Return the uncompressed size of the ZipFile (only works for files on disk, not in assets)
   *
   * @return -1 on failure
   */
  private long getUncompressedSize(String zipFilePath, String charset) {
    long totalSize = 0;
    try {
      ZipFile zipFile = new ZipFile(zipFilePath, Charset.forName(charset));
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        long size = entry.getSize();
        if (size != -1) {
          totalSize += size;
        }
      }
      zipFile.close();
    } catch (IOException ignored) {
      return -1;
    }
    return totalSize;
  }

  /**
   * Returns the exception stack trace as a string
   */
  private String getStackTrace(Exception e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  private void ensureZipPathSafety(final File fout, final String destDirectory) throws Exception {
    String destDirCanonicalPath = (new File(destDirectory)).getCanonicalPath();
    String canonicalPath = fout.getCanonicalPath();
    if (!canonicalPath.startsWith(destDirCanonicalPath)) {
      throw new Exception(String.format("Found Zip Path Traversal Vulnerability with %s", canonicalPath));
    }
  }

}
