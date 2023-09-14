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

public enum LoaderType {

	FABRIC("net.fabricmc.fabric-loader"),
	QUILT("org.quiltmc.quilt-loader");

	private final String name;
	private final String mavenUid;

	private String localizedName;

	private LoaderType(String uid) {
		this.name = this.name().toLowerCase();
		this.mavenUid = uid;
	}

	public String getName() {
		return this.name;
	}

	public String getMavenUid() {
		return this.mavenUid;
	}

	public String getLocalizedName() {
		if (this.localizedName == null) {
			this.localizedName = Localization.get("gui.loader.type." + this.name);
		}

		return this.localizedName;
	}

	public static LoaderType of(String name) {
		return valueOf(name.toUpperCase());
	}
}
