// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.packaging;

import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.VersionComparatorUtil;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.jetbrains.python.PythonHelpersLocator;
import com.jetbrains.python.sdk.PythonSdkType;
import com.jetbrains.python.sdk.flavors.CondaEnvSdkFlavor;
import com.jetbrains.python.sdk.flavors.PyCondaRunKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemDependent;

import java.io.File;
import java.util.*;

@State(name = "PyCondaPackageService", storages = @Storage(value="conda_packages.xml", roamingType = RoamingType.DISABLED))
public class PyCondaPackageService implements PersistentStateComponent<PyCondaPackageService> {
  private static final Logger LOG = Logger.getInstance(PyCondaPackageService.class);
  public Set<String> CONDA_CHANNELS = ContainerUtil.newConcurrentSet();
  public long LAST_TIME_CHECKED = 0;
  @Nullable @SystemDependent public String PREFERRED_CONDA_PATH = null;

  @Override
  public PyCondaPackageService getState() {
    return this;
  }

  @Override
  public void loadState(@NotNull PyCondaPackageService state) {
    XmlSerializerUtil.copyBean(state, this);
  }

  public static PyCondaPackageService getInstance() {
    return ServiceManager.getService(PyCondaPackageService.class);
  }

  public void loadAndGetPackages(boolean force) {
    if (PyCondaPackageCache.getInstance().getPackageNames().isEmpty() || force) {
      updatePackagesCache();
    }
  }

  public Set<String> loadAndGetChannels() {
    if (CONDA_CHANNELS.isEmpty()) {
      updateChannels();
    }
    return CONDA_CHANNELS;
  }

  public void addChannel(@NotNull final String url) {
    CONDA_CHANNELS.add(url);
  }

  public void removeChannel(@NotNull final String url) {
    CONDA_CHANNELS.remove(url);
  }

  @Nullable
  public static String getCondaPython() {
    final String conda = getSystemCondaExecutable();
    if (conda != null) {
      final String python = getCondaBasePython(conda);
      if (python != null) return python;
    }
    return getCondaExecutableByName(getPythonName());
  }

  @Nullable
  public static String getCondaBasePython(@NotNull String systemCondaExecutable) {
    final VirtualFile condaFile = LocalFileSystem.getInstance().findFileByPath(systemCondaExecutable);
    if (condaFile != null) {
      final VirtualFile condaDir = SystemInfo.isWindows ? condaFile.getParent().getParent() : condaFile.getParent();
      final VirtualFile python = condaDir.findChild(getPythonName());
      if (python != null) {
        return python.getPath();
      }
    }
    return null;
  }

  @NotNull
  private static String getPythonName() {
    return SystemInfo.isWindows ? "python.exe" : "python";
  }

  @Nullable
  public static String getSystemCondaExecutable() {
    final String condaName = SystemInfo.isWindows ? "conda.exe" : "conda";
    final File condaInPath = PathEnvironmentVariableUtil.findInPath(condaName);
    if (condaInPath != null) return condaInPath.getPath();
    return getCondaExecutableByName(condaName);
  }

  @Nullable
  public static String getCondaExecutable(@Nullable String sdkPath) {
    if (sdkPath == null) {
      return null;
    }

    String condaPath = findCondaExecutableRelativeToEnv(sdkPath);

    if (condaPath != null) return condaPath;

    if (StringUtil.isNotEmpty(getInstance().PREFERRED_CONDA_PATH)) {
      return getInstance().PREFERRED_CONDA_PATH;
    }

    return getSystemCondaExecutable();
  }

  private static String findCondaExecutableRelativeToEnv(@NotNull String sdkPath) {
    VirtualFile sdkHomeDir = StandardFileSystems.local().findFileByPath(sdkPath);
    if (sdkHomeDir == null) {
      return null;
    }
    final VirtualFile bin = sdkHomeDir.getParent();
    String condaName = "conda";
    if (SystemInfo.isWindows) {
      condaName = bin.findChild("envs") != null ? "conda.exe" : "conda.bat";
    }
    final VirtualFile conda = bin.findChild(condaName);
    if (conda != null) return conda.getPath();
    final VirtualFile condaFolder = bin.getParent();

    return findExecutable(condaName, condaFolder);
  }

  @Nullable
  public static String getCondaExecutableByName(@NotNull final String condaName) {
    final VirtualFile userHome = LocalFileSystem.getInstance().findFileByPath(SystemProperties.getUserHome().replace('\\', '/'));
    if (userHome != null) {
      for (String root : CondaEnvSdkFlavor.CONDA_DEFAULT_ROOTS) {
        VirtualFile condaFolder = userHome.findChild(root);
        String executableFile = findExecutable(condaName, condaFolder);
        if (executableFile != null) return executableFile;
        if (SystemInfo.isWindows) {
          final VirtualFile appData = userHome.findFileByRelativePath("AppData\\Local\\Continuum\\" + root);
          executableFile = findExecutable(condaName, appData);
          if (executableFile != null) return executableFile;
          condaFolder = LocalFileSystem.getInstance().findFileByPath("C:\\" + root);
          executableFile = findExecutable(condaName, condaFolder);
          if (executableFile != null) return executableFile;
        }
        else {
          final String systemWidePath = "/opt/anaconda";
          condaFolder = LocalFileSystem.getInstance().findFileByPath(systemWidePath);
          executableFile = findExecutable(condaName, condaFolder);
          if (executableFile != null) return executableFile;
        }
      }
    }

    return null;
  }

  @Nullable
  private static String findExecutable(String condaName, @Nullable final VirtualFile condaFolder) {
    if (condaFolder != null) {
      final VirtualFile binFolder = condaFolder.findChild(SystemInfo.isWindows ? "Scripts" : "bin");
      if (binFolder != null) {
        final VirtualFile bin = binFolder.findChild(condaName);
        if (bin != null) {
          String directoryPath = bin.getPath();
          final String executableFile = PythonSdkType.getExecutablePath(directoryPath, condaName);
          if (executableFile != null) {
            return executableFile;
          }
        }
      }
    }
    return null;
  }

  public void updatePackagesCache() {
    final String condaPython = getCondaPython();
    if (condaPython == null) {
      return;
    }
    final String path = PythonHelpersLocator.getHelperPath("conda_packaging_tool.py");
    final ProcessOutput output;
    try {
      output = PyCondaRunKt.runCondaPython(condaPython, Arrays.asList(path, "listall"));
    }
    catch (PyExecutionException e) {
      LOG.warn("Failed to get list of conda packages. " + e);
      return;
    }

    final Multimap<String, String> nameToVersions =
      Multimaps.newSortedSetMultimap(new HashMap<>(), () -> new TreeSet<>(VersionComparatorUtil.COMPARATOR.reversed()));
    for (String line : output.getStdoutLines()) {
      final List<String> split = StringUtil.split(line, "\t");
      if (split.size() < 2) continue;
      nameToVersions.put(split.get(0), split.get(1));
    }
    PyCondaPackageCache.reload(nameToVersions);
    LAST_TIME_CHECKED = System.currentTimeMillis();
  }

  @NotNull
  public List<String> getPackageVersions(@NotNull final String packageName) {
    return ContainerUtil.notNullize(PyCondaPackageCache.getInstance().getVersions(packageName));
  }

  public void updateChannels() {
    final String condaPython = getCondaPython();
    if (condaPython == null) return;
    final String path = PythonHelpersLocator.getHelperPath("conda_packaging_tool.py");
    final ProcessOutput output;
    try {
      output = PyCondaRunKt.runCondaPython(condaPython, Arrays.asList(path, "channels"));
    }
    catch (PyExecutionException e) {
      LOG.warn("Failed to update conda channels. " + e);
      return;
    }
    final List<String> lines = output.getStdoutLines();
    CONDA_CHANNELS.addAll(lines);
    LAST_TIME_CHECKED = System.currentTimeMillis();
  }
}
