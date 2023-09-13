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

	FABRIC("fabric", "net.fabricmc.fabric-loader"),
	QUILT("quilt", "org.quiltmc.quilt-loader");

	private final String name;
	private final String fancyName;
	private final String mavenUid;

	private LoaderType(String name, String uid) {
		this.name = name;
		this.fancyName = this.name.substring(0, 1).toUpperCase() + this.name.substring(1);
		this.mavenUid = uid;
	}

	public String getName() {
		return name;
	}

	public String getMavenUid() {
		return mavenUid;
	}

	public String getFancyName() {
		return fancyName;
	}

	public static LoaderType of(String name) {
		return valueOf(name.toUpperCase());
	}
}
