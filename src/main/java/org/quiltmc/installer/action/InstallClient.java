/*
 * Copyright 2021 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.installer.action;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.installer.OsPaths;
import org.quiltmc.parsers.json.JsonReader;
import org.quiltmc.installer.GameSide;
import org.quiltmc.installer.Gsons;
import org.quiltmc.installer.LaunchJson;
import org.quiltmc.installer.LauncherProfiles;
import org.quiltmc.installer.LauncherType;
import org.quiltmc.installer.LoaderType;
import org.quiltmc.installer.MmcPackCreator;

/**
 * An action which installs a new client instance.
 */
public final class InstallClient extends Action<InstallMessageType> {
	private final String minecraftVersion;
	private final LauncherType launcherType;
	private final LoaderType loaderType;
	@Nullable
	private final String loaderVersion;
	private final String intermediary;
	private final String installDir;
	private final boolean generateProfile;
	private final boolean copyProfilePath;
	private Path installDirPath;

	InstallClient(String minecraftVersion, LauncherType launcherType, LoaderType loaderType, @Nullable String loaderVersion, String intermediary, String installDir, boolean generateProfile, boolean copyProfilePath) {
		this.minecraftVersion = minecraftVersion;
		this.launcherType = launcherType;
		this.loaderType = loaderType;
		this.loaderVersion = loaderVersion;
		this.intermediary = intermediary;
		this.installDir = installDir;
		this.generateProfile = generateProfile;
		this.copyProfilePath = copyProfilePath;
	}

	@Override
	public void run(Consumer<InstallMessageType> statusTracker) {
		switch (this.launcherType) {
		case OFFICIAL:
			this.installOfficial(statusTracker);
			break;
		case MULTIMC:
			this.installMultimc(statusTracker);
			break;
		default:
			throw new RuntimeException("don't know how to install into " + this.launcherType);
		}
	}

	private void installOfficial(Consumer<InstallMessageType> statusTracker) {
		Path installDir;

		if (this.installDir == null) {
			installDir = OsPaths.getDefaultInstallationDir();
		} else {
			installDir = Paths.get(this.installDir);
		}

		this.installDirPath = installDir;

		println(String.format("Installing Minecraft client at: %s", installDir));

		if (this.loaderVersion != null) {
			println(String.format("Installing Minecraft client of version %s with loader version %s", this.minecraftVersion, this.loaderVersion));
		} else {
			println(String.format("Installing Minecraft client of version %s", this.minecraftVersion));
		}

		/*
		 * Installing the client involves a few steps:
		 * 1. Get the specified launcher directory
		 * 2. Lookup if the minecraftVersion specified exists and then if it has intermediary
		 * 3. Lookup if the specified loaderVersion exists, looking up the latest if null
		 * 4. Get the launch metadata for the specified version of loader
		 * 5. Game version and profile name into the launch json
		 * 6. Write it
		 * 7. (Optional) create profile if needed
		 */

		CompletableFuture<MinecraftInstallation.InstallationInfo> installationInfoFuture = MinecraftInstallation.getInfo(GameSide.CLIENT, this.minecraftVersion, this.loaderType, this.loaderVersion);

		installationInfoFuture.thenCompose(installationInfo -> LaunchJson.get(installationInfo.manifest().getVersion(this.minecraftVersion)).thenCompose(vanillaLaunchJson -> LaunchJson.get(GameSide.CLIENT, installationInfo.manifest().getVersion(this.minecraftVersion), this.loaderType, installationInfo.loaderVersion()).thenAccept(launchJson -> {
			println("Creating profile launch json");

			Map<String, Object> vanillaLaunchJsonMap;
			Map<String, Object> launchJsonMap;
			try {
				vanillaLaunchJsonMap = (Map<String, Object>) Gsons.read(JsonReader.json(vanillaLaunchJson));
				launchJsonMap = (Map<String, Object>) Gsons.read(JsonReader.json(launchJson));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			if (!vanillaLaunchJsonMap.containsKey("id")) {
				throw new RuntimeException("vanilla launcher profile json is missing the profile id!");
			}
			if (!launchJsonMap.containsKey("id")) {
				throw new RuntimeException("launcher profile json is missing the profile id!");
			}

			String vanillaProfileName = (String) vanillaLaunchJsonMap.get("id");
			String profileName = (String) launchJsonMap.get("id");

			// Directories
			Path versionsDir = this.installDirPath.resolve("versions");
			Path vanillaProfileDir = versionsDir.resolve(vanillaProfileName);
			Path vanillaProfileJson = vanillaProfileDir.resolve(vanillaProfileName + ".json");
			Path profileDir = versionsDir.resolve(profileName);
			Path profileJson = profileDir.resolve(profileName + ".json");

			// Nuke everything that already exists
			clearProfileDir(vanillaProfileDir);
			clearProfileDir(profileDir);

			/*
			 * Abuse some of the vanilla launcher's undefined behavior:
			 *
			 * Assumption is the profile name is the same as the maven artifact.
			 * The profile name we set is a combination of two artifacts (loader + mappings).
			 * As long as the jar file exists of the same name the launcher won't complain.
			 */

			// Make our pretender jar
			makePretenderJar(vanillaProfileDir, vanillaProfileName);
			makePretenderJar(profileDir, profileName);

			// Write the launch json
			writeLaunchJson(vanillaProfileJson, vanillaLaunchJson);
			writeLaunchJson(profileJson, launchJson);

			// Create the profile - this is typically set by default
			if (this.generateProfile) {
				try {
					println("Creating new profile");
					LauncherProfiles.updateProfiles(this.installDirPath, profileName, this.minecraftVersion, loaderType);
				} catch (IOException e) {
					throw new UncheckedIOException(e); // Handle via exceptionally
				}
			}
			statusTracker.accept(InstallMessageType.SUCCEED);
			println("Completed installation");
		}))).exceptionally(e -> {
			eprintln("Failed to install client");
			e.printStackTrace();
			statusTracker.accept(InstallMessageType.FAIL);
			return null;
		}).join();
	}

	private static void clearProfileDir(Path dir) {
		try {
			Files.walk(dir).map(Path::toFile).sorted((o1, o2) -> -o1.compareTo(o2)).forEach(File::delete);
		 } catch (IOException ignored) {
			//
		}
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // Handle via exceptionally
		}
	}

	private static void makePretenderJar(Path dir, String profileName) {
		try {
			Files.createFile(dir.resolve(profileName + ".jar"));
		} catch (FileAlreadyExistsException ignore) {
			// Pretender jar already exists
		} catch (IOException e) {
			throw new UncheckedIOException(e); // Handle via exceptionally
		}
	}

	private static void writeLaunchJson(Path path, String json) {
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
			writer.append(json);
		} catch (IOException e) {
			throw new UncheckedIOException(e); // Handle via exceptionally
		}
	}

	private void installMultimc(Consumer<InstallMessageType> statusTracker) {
		CompletableFuture<MinecraftInstallation.InstallationInfo> installationInfoFuture = MinecraftInstallation.getInfo(GameSide.CLIENT, this.minecraftVersion, this.loaderType, this.loaderVersion);

		installationInfoFuture.thenAccept(installationInfo -> {
			MmcPackCreator.compileMmcZip(
					Paths.get(this.installDir),
					this.minecraftVersion,
					this.loaderType,
					this.loaderVersion,
					this.intermediary,
					installationInfo.manifest(),
					this.copyProfilePath
			);
			statusTracker.accept(InstallMessageType.SUCCEED);
		}).exceptionally(e -> {
			eprintln("Failed to generate multimc pack");
			e.printStackTrace();
			statusTracker.accept(InstallMessageType.FAIL);
			return null;
		}).join();
	}
}
