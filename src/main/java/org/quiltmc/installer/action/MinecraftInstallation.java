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

import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.installer.GameSide;
import org.quiltmc.installer.GameVersion;
import org.quiltmc.installer.Intermediary;
import org.quiltmc.installer.LoaderType;
import org.quiltmc.installer.OrnitheMeta;
import org.quiltmc.installer.VersionManifest;

public final class MinecraftInstallation {
	/**
	 * Verifies the specified game version exists, has intermediary mappings.
	 * If the loader version is not specified, also looks up the loader version.
	 *
	 * @param gameVersion the game version
	 * @param loaderVersion the override for the loader version to use
	 * @return a future containing the loader version to use
	 */
	public static CompletableFuture<InstallationInfo> getInfo(GameSide side, String minecraftVersion, @Nullable GameVersion gameVersion, LoaderType loaderType, @Nullable String loaderVersion, OptionalInt intermediaryGen, @Nullable Intermediary intermediaryVersion) {
		CompletableFuture<VersionManifest> versionManifestFuture = VersionManifest.create(intermediaryGen).thenApply(manifest -> {
			if (manifest.getVersion(minecraftVersion) != null) {
				return manifest;
			}

			throw new IllegalArgumentException(String.format("Minecraft version %s does not exist", minecraftVersion));
		});

		Set<OrnitheMeta.Endpoint<?>> endpoints = new HashSet<>();
		endpoints.add(OrnitheMeta.gameVersionsEndpoint(intermediaryGen));
		endpoints.add(OrnitheMeta.loaderVersionsEndpoint(intermediaryGen, loaderType));
		endpoints.add(OrnitheMeta.intermediaryVersionsEndpoint(intermediaryGen));

		CompletableFuture<OrnitheMeta> metaFuture = OrnitheMeta.create(OrnitheMeta.ORNITHE_META_URL, endpoints);

		// Verify that the specified MC version can be installed
		CompletableFuture<GameVersion> gameVersionFuture = metaFuture.thenApply(meta -> {
			if (gameVersion != null) {
				return gameVersion;
			}

			List<GameVersion> gameVersions = meta.getEndpoint(OrnitheMeta.gameVersionsEndpoint(intermediaryGen));

			for (GameVersion gv : gameVersions) {
				if (gv.hasSide(side) && gv.getId().equals(minecraftVersion)) {
					return gv;
				}
			}

			throw new IllegalArgumentException(String.format("Minecraft version %s does not exist or can not be installed on the ", minecraftVersion, side.id()));
		});

		// Verify we actually have intermediary for the specified version
		CompletableFuture<Intermediary> intermediaryFuture = metaFuture.thenApply(meta -> {
			if (intermediaryVersion != null) {
				return intermediaryVersion;
			}

			List<Intermediary> intermediaryVersions = meta.getEndpoint(OrnitheMeta.intermediaryVersionsEndpoint(intermediaryGen));

			for (Intermediary iv : intermediaryVersions) {
				if (iv.getGameVersion().equals(minecraftVersion)) {
					return iv;
				}
			}

			throw new IllegalArgumentException(String.format("Minecraft version %s exists but has no intermediary", minecraftVersion));
		});

		CompletableFuture<String> loaderVersionFuture = metaFuture.thenApply(meta -> {
			List<String> versions = meta.getEndpoint(OrnitheMeta.loaderVersionsEndpoint(intermediaryGen, loaderType));

			if (loaderVersion != null) {
				if (!versions.contains(loaderVersion)) {
					throw new IllegalStateException(String.format("Specified loader version %s was not found", loaderVersion));
				}

				return versions.get(versions.indexOf(loaderVersion));
			}

			if (versions.size() == 0) {
				throw new IllegalStateException("No loader versions were found");
			}

			// Choose latest stable version
			return versions.stream().filter(version -> !version.contains("-")).findFirst().get();
		});

		return CompletableFuture.allOf(versionManifestFuture, gameVersionFuture, intermediaryFuture, loaderVersionFuture).thenApply(_v -> {
			try {
				return new InstallationInfo(gameVersionFuture.get(), loaderVersionFuture.get(), intermediaryGen, intermediaryFuture.get(), versionManifestFuture.get());
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private MinecraftInstallation() {
	}

	public static final class InstallationInfo {
		private final GameVersion gameVersion;
		private final String loaderVersion;
		private final OptionalInt intermediaryGen;
		private final Intermediary intermediary;
		private final VersionManifest manifest;

		InstallationInfo(GameVersion gameVersion, String loaderVersion, OptionalInt intermediaryGen, Intermediary intermediary, VersionManifest manifest) {
			this.gameVersion = gameVersion;
			this.loaderVersion = loaderVersion;
			this.intermediaryGen = intermediaryGen;
			this.intermediary = intermediary;
			this.manifest = manifest;
		}

		public GameVersion gameVersion() {
			return this.gameVersion;
		}

		public String loaderVersion() {
			return this.loaderVersion;
		}

		public OptionalInt intermediaryGen() {
			return this.intermediaryGen;
		}

		public Intermediary intermediary() {
			return this.intermediary;
		}

		public VersionManifest manifest() {
			return this.manifest;
		}
	}
}
