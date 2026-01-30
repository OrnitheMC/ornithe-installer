/*
 * Copyright 2023 QuiltMC
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

package org.quiltmc.installer;

public class LWJGL {

	private final String version;
	private final String url;

	public LWJGL(String version, String url) {
		this.version = version;
		this.url = url;
	}

	public String getUid() {
		return switch (this.getMajorVersion()) {
			case "2" -> "org.lwjgl";
			case "3" -> "org.lwjgl3";
			default -> throw new IllegalArgumentException("unknown major LWJGL version " + this.getMajorVersion());
		};
	}

	public String getVersion() {
		return this.version;
	}

	public String getMajorVersion() {
		return this.version.substring(0, this.version.indexOf('.'));
	}

	public String getUrl() {
		return this.url;
	}

	public boolean isCustom() {
		return !this.url.startsWith("https://libraries.minecraft.net/");
	}
}
