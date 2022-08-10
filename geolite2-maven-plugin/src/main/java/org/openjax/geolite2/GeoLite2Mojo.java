/* Copyright (c) 2020 OpenJAX
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * You should have received a copy of The MIT License (MIT) along with this
 * program. If not, see <http://opensource.org/licenses/MIT/>.
 */

package org.openjax.geolite2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.libj.io.FileUtil;
import org.libj.net.URLs;
import org.libj.util.StringPaths;
import org.libj.util.function.Throwing;
import org.openjax.maven.mojo.BaseMojo;

import com.maxmind.db.InvalidDatabaseException;
import com.maxmind.geoip2.DatabaseReader;

@Mojo(name="update", defaultPhase=LifecyclePhase.GENERATE_RESOURCES)
@Execute(goal="update")
public final class GeoLite2Mojo extends BaseMojo {
  private static final String[] databases = {"GeoLite2-ASN", "GeoLite2-City", "GeoLite2-Country"};
  private static final String[] extensions = {"tar.gz"};

  @Parameter(property="database", required=true)
  private String database;

  @Parameter(property="extension", required=true)
  private String extension;

  @Parameter(property="licenseKey", required=true)
  private String licenseKey;

  @Parameter(property="destDir", required=true)
  private File destDir;

  @Parameter(property="cacheDir", required=true)
  private File cacheDir;

  @Parameter(property="force", defaultValue="false")
  private boolean force;

  @Parameter(defaultValue="${project}", required=true, readonly=true)
  protected MavenProject project;

  @Parameter(defaultValue="${settings.offline}", required=true, readonly=true)
  protected boolean offline;

  private File getGeoLiteDb(final boolean offline, final boolean failOnNoOp) throws IOException, MojoExecutionException {
    if (Arrays.binarySearch(databases, database) < 0)
      throw new MojoExecutionException("<database> must be one of: " + Arrays.toString(databases));

    if (Arrays.binarySearch(extensions, extension) < 0)
      throw new MojoExecutionException("<extension> must be one of: " + Arrays.toString(extensions));

    final String name = database + ".mmdb";
    final File dbFile = new File(cacheDir, name);
    if (dbFile.exists() && !force) {
      try {
        new DatabaseReader.Builder(dbFile).build();
      }
      catch (final InvalidDatabaseException e) {
        force = true;
      }
    }

    if (offline) {
      if (force || !dbFile.exists())
        throw new MojoExecutionException("Resolution of Last-Modified is required, and cannot be done in offline mode");

      if (failOnNoOp)
        throw new MojoExecutionException("Failing due to offline mode (failOnNoOp=true)");

      if (dbFile.exists()) {
        getLog().warn("Not checking Last-Modified time due to offline mode");
        return dbFile;
      }
    }

    final URL url = new URL("https://download.maxmind.com/app/geoip_download?edition_id=" + database + "&license_key=" + licenseKey + "&suffix=" + extension);
    final long urlLastModified = url.openConnection().getLastModified();
    if (force) {
      if (dbFile.exists() && !dbFile.delete())
        throw new IOException("Unable to delete " + dbFile.getAbsolutePath());

      cacheDir.mkdirs();
    }
    else if (dbFile.exists()) {
      if (offline) {
        getLog().warn("Not checking Last-Modified time due to offline mode");
        return dbFile;
      }

      if (urlLastModified != 0 && urlLastModified <= dbFile.lastModified()) {
        getLog().info(name + " is up-to-date");
        return dbFile;
      }
    }
    else {
      cacheDir.mkdirs();
    }

    getLog().info("Downloading: " + url);
    try (final TarArchiveInputStream in = new TarArchiveInputStream(new GZIPInputStream(url.openStream()))) {
      for (TarArchiveEntry entry; (entry = in.getNextTarEntry()) != null;) { // [X]
        if (!entry.isDirectory()) {
          if (dbFile.exists() && !dbFile.delete())
            throw new IOException("Unable to delete " + dbFile.getAbsolutePath());

          final File file = new File(cacheDir, entry.getName());
          file.getParentFile().mkdirs();
          try (final FileOutputStream out = new FileOutputStream(file)) {
            IOUtils.copy(in, out);
          }
        }
      }
    }
    catch (final IOException e) {
      if (!dbFile.exists())
        throw e;

      getLog().warn("Unable to update " + URLs.getName(url) + " due to: " + e.getMessage());
      return dbFile;
    }

    Files.list(cacheDir.toPath()).forEach(new Consumer<Path>() {
      @Override
      public void accept(final Path t) {
        if (StringPaths.getName(t.toString()).startsWith("GeoLite2-City")) {
          try {
            final File tempFile = new File(t.toFile(), name);
            new DatabaseReader.Builder(tempFile).build();
            Files.move(tempFile.toPath(), dbFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(dbFile.toPath(), FileTime.fromMillis(urlLastModified));
            FileUtil.deleteAll(t);
          }
          catch (final IOException e) {
            dbFile.delete();
            Throwing.rethrow(e);
          }
        }
      }
    });

    return dbFile;
  }

  @Override
  public void execute(final Configuration configuration) throws MojoExecutionException, MojoFailureException {
    try {
      final File file = getGeoLiteDb(offline, configuration.getFailOnNoOp());
      if (!destDir.exists() && !destDir.mkdirs())
        throw new MojoExecutionException("Unable to mkdir " + destDir.getAbsolutePath());

      Files.copy(file.toPath(), new File(destDir, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    catch (final IOException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    final Resource resource = new Resource();
    resource.setDirectory(destDir.getAbsolutePath());
    if (isInTestPhase())
      project.getTestResources().add(resource);
    else
      project.getResources().add(resource);
  }
}