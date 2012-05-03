/*
 Copyright 2011 Software Freedom Conservancy.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.openqa.selenium.browserlaunchers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.openqa.selenium.Beta;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.net.UrlChecker;
import org.openqa.selenium.os.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the life and death of a native executable driver server.
 */
public class DriverService {

  /**
   * The base URL for the managed server.
   */
  private final URL url;

  /**
   * Controls access to {@link #process}.
   */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * A reference to the current child process. Will be {@code null} whenever this service is not
   * running. Protected by {@link #lock}.
   */
  private CommandLine process = null;

  private final String executable;
  private final ImmutableList<String> args;
  private final ImmutableMap<String, String> environment;

  /**
   *
   * @param executable The driver executable.
   * @param port Which port to start the driver on.
   * @param environment The environment for the launched server.
   * @param logFile Optional file to dump logs to.
   * @throws IOException If an I/O error occurs.
   */
  protected DriverService(File executable, int port,
      ImmutableMap<String, String> environment, File logFile) throws IOException {
    this.executable = executable.getCanonicalPath();
    args = buildArgsFrom(port, logFile);
    url = new URL(String.format("http://localhost:%d", port));
    this.environment = environment;
  }

  private ImmutableList<String> buildArgsFrom(int port, File logFile) {
    ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
    argsBuilder.add(String.format("--port=%d", port));
    if (logFile != null) {
      argsBuilder.add(String.format("--log-path=%s", logFile.getAbsolutePath()));
    }
    return argsBuilder.build();
  }

  /**
   * @return The base URL for the managed driver server.
   */
  public URL getUrl() {
    return url;
  }

  protected static File findExecutable(String exeName, String exeProperty, String exeDocs, String exeDownload) {
    String defaultPath = CommandLine.findExecutable(exeName);
    String exePath = System.getProperty(exeProperty, defaultPath);
    checkState(exePath != null,
        "The path to the driver executable must be set by the %s system property;"
            + " for more information, see %s. "
            + "The latest version can be downloaded from %s",
            exeProperty, exeDocs, exeDownload);

    File exe = new File(exePath);
    checkState(exe.exists(),
        "The %s system property defined driver executable does not exist: %s",
        exeProperty, exe.getAbsolutePath());
    checkState(!exe.isDirectory(),
        "The %s system property defined driver executable is a directory: %s",
        exeProperty, exe.getAbsolutePath());
    // TODO(jleyba): Check file.canExecute() once we support Java 1.6
    // checkState(exe.canExecute(),
    // "The %s system property defined driver is not executable: %s",
    // exeProperty, exe.getAbsolutePath());
    return exe;
  }

  /**
   * Checks whether the driver child process is currently running.
   *
   * @return Whether the driver child process is still running.
   */
  public boolean isRunning() {
    lock.lock();
    try {
      if (process == null) {
        return false;
      }
      process.destroy();
      return false;
    } catch (IllegalThreadStateException e) {
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Starts this service if it is not already running. This method will block until the server has
   * been fully started and is ready to handle commands.
   *
   * @throws IOException If an error occurs while spawning the child process.
   * @see #stop()
   */
  public void start() throws IOException {
    lock.lock();
    try {
      if (process != null) {
        return;
      }
      process = new CommandLine(this.executable, args.toArray(new String[] {}));
      process.setEnvironmentVariables(environment);
      process.copyOutputTo(System.err);
      process.executeAsync();

      URL status = new URL(url.toString() + "/status");
      new UrlChecker().waitUntilAvailable(20, SECONDS, status);
    } catch (UrlChecker.TimeoutException e) {
      throw new WebDriverException("Timed out waiting for driver server to start.", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops this service is it is currently running. This method will attempt to block until the
   * server has been fully shutdown.
   *
   * @see #start()
   */
  public void stop() {
    lock.lock();
    try {
      if (process == null) {
        return;
      }
      URL killUrl = new URL(url.toString() + "/shutdown");
      new UrlChecker().waitUntilUnavailable(3, SECONDS, killUrl);
      process.destroy();
    } catch (MalformedURLException e) {
      throw new WebDriverException(e);
    } catch (UrlChecker.TimeoutException e) {
      throw new WebDriverException("Timed out waiting for driver server to shutdown.", e);
    } finally {
      process = null;
      lock.unlock();
    }
  }

  /**
   * Builder used to configure new {@link DriverService} instances.
   */
  public abstract static class Builder {

    protected int port = 0;
    protected File exe = null;
    protected ImmutableMap<String, String> environment = ImmutableMap.of();
    protected File logFile;

    /**
     * Sets which driver executable the builder will use.
     *
     * @param file The executable to use.
     * @return A self reference.
     */
    public Builder usingDriverExecutable(File file) {
      checkNotNull(file);
      checkArgument(file.exists(), "Specified driver executable does not exist: %s",
          file.getPath());
      checkArgument(!file.isDirectory(), "Specified driver executable is a directory: %s",
          file.getPath());
      // TODO(jleyba): Check file.canExecute() once we support Java 1.6
      // checkArgument(file.canExecute(), "File is not executable: %s", file.getPath());
      this.exe = file;
      return this;
    }

    /**
     * Sets which port the driver server should be started on. A value of 0 indicates that any
     * free port may be used.
     *
     * @param port The port to use; must be non-negative.
     * @return A self reference.
     */
    public Builder usingPort(int port) {
      checkArgument(port >= 0, "Invalid port number: %d", port);
      this.port = port;
      return this;
    }

    /**
     * Configures the driver server to start on any available port.
     *
     * @return A self reference.
     */
    public Builder usingAnyFreePort() {
      this.port = 0;
      return this;
    }

    /**
     * Defines the environment for the launched driver server. These
     * settings will be inherited by every browser session launched by the
     * server.
     *
     * @param environment A map of the environment variables to launch the
     *     server with.
     * @return A self reference.
     */
    @Beta
    public Builder withEnvironment(Map<String, String> environment) {
      this.environment = ImmutableMap.copyOf(environment);
      return this;
    }
    
    public Builder withLogFile(File logFile) {
      this.logFile = logFile;
      return this;
    }

    /**
     * Creates a new binary to manage the driver server. Before creating a new binary, the
     * builder will check that either the user defined the location of the driver executable
     * through {@link #usingDriverExecutable(File) the API} or with the
     * {@code webdriver.chrome.driver} system property.
     *
     * @return The new binary.
     */
    public DriverService build() {
      if (port == 0) {
        port = PortProber.findFreePort();
      }

      checkState(exe != null, "Path to the driver executable not specified");

      try {
        return buildDriverService();
      } catch (IOException e) {
        throw new WebDriverException(e);
      }
    }
    
    abstract protected DriverService buildDriverService() throws IOException;
  }
}