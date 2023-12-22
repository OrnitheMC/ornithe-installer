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

package org.quiltmc.installer.gui.swing;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.installer.*;
import org.quiltmc.installer.action.Action;
import org.quiltmc.installer.action.InstallClient;

final class ClientPanel extends AbstractPanel implements Consumer<InstallClient.MessageType> {
	private final JComboBox<String> minecraftVersionSelector;
	private final JComboBox<LauncherLabel> launcherTypeSelector;
	private final JComboBox<LoaderLabel> loaderTypeSelector;
	private final JComboBox<String> loaderVersionSelector;
	private final JCheckBox showSnapshotsCheckBox;
	private final JCheckBox showLoaderBetasCheckBox;
	private final JTextField installLocation;
	private final JButton selectInstallationLocation;
	//private JComponent telemetryCheckBox;
	private JCheckBox generateProfileCheckBox;
	private final JButton installButton;
	private boolean showSnapshots;
	private boolean showLoaderBetas;
	private boolean generateProfile;

	ClientPanel(SwingInstaller gui) {
		super(gui);

		// Minecraft version
		{
			JComponent row1 = this.addRow();

			row1.add(new JLabel(Localization.get("gui.game.version")));
			row1.add(this.minecraftVersionSelector = new JComboBox<>());
			// Set the preferred size so we do not need to repack the window
			// The chosen width is so we are wider than b1.9-pre4-201110131434
			this.minecraftVersionSelector.setPreferredSize(new Dimension(220, 26));
			this.minecraftVersionSelector.addItem(Localization.get("gui.install.loading"));
			this.minecraftVersionSelector.setEnabled(false);

			row1.add(this.showSnapshotsCheckBox = new JCheckBox(Localization.get("gui.game.version.snapshots")));
			this.showSnapshotsCheckBox.setEnabled(false);
			this.showSnapshotsCheckBox.addItemListener(e -> {
				// Versions are already loaded, repopulate the combo box
				if (this.manifest() != null) {
					this.showSnapshots = e.getStateChange() == ItemEvent.SELECTED;
					populateMinecraftVersions(GameSide.CLIENT, this.minecraftVersionSelector, this.manifest(), this.intermediaryVersions(), this.showSnapshots);
				}
			});
		}

		{
			JComponent rowOnePointOne = this.addRow();

			rowOnePointOne.add(new JLabel(Localization.get("gui.launcher.type")));
			rowOnePointOne.add(this.launcherTypeSelector = new JComboBox<>());
			this.launcherTypeSelector.setPreferredSize(new Dimension(200, 26));
			for (LauncherType type : LauncherType.values()) {
				this.launcherTypeSelector.addItem(new LauncherLabel(type));
			}

			this.launcherTypeSelector.setEnabled(true);
		}

		// Loader type
		{
			JComponent row2 = this.addRow();

			row2.add(new JLabel(Localization.get("gui.loader.type")));
			row2.add(this.loaderTypeSelector = new JComboBox<>());
			this.loaderTypeSelector.setPreferredSize(new Dimension(200, 26));
			for (LoaderType type : LoaderType.values()) {
				this.loaderTypeSelector.addItem(new LoaderLabel(type));
			}
			this.loaderTypeSelector.setEnabled(true);
		}

		// Loader version
		{
			JComponent row3 = this.addRow();

			row3.add(new JLabel(Localization.get("gui.loader.version")));
			row3.add(this.loaderVersionSelector = new JComboBox<>());
			this.loaderVersionSelector.setPreferredSize(new Dimension(200, 26));
			this.loaderVersionSelector.addItem(Localization.get("gui.install.loading"));
			this.loaderVersionSelector.setEnabled(false);

			row3.add(this.showLoaderBetasCheckBox = new JCheckBox(Localization.get("gui.loader.version.betas")));
			this.showLoaderBetasCheckBox.setEnabled(false);
			this.showLoaderBetasCheckBox.addItemListener(e -> {
				if (this.loaderVersions() != null) {
					this.showLoaderBetas = e.getStateChange() == ItemEvent.SELECTED;
					populateLoaderVersions(GameSide.CLIENT, this.loaderVersionSelector, this.loaderVersions(this.loaderType()), this.showLoaderBetas);
				}
			});

			this.loaderTypeSelector.addItemListener(e -> {
				if (this.loaderVersions() != null) {
					populateLoaderVersions(GameSide.CLIENT, this.loaderVersionSelector, this.loaderVersions(this.loaderType()), this.showLoaderBetas);
				}
			});
		}

		// Install location
		{
			JComponent row4 = this.addRow();

			row4.add(new JLabel(Localization.get("gui.install-location")));
			row4.add(this.installLocation = new JTextField());
			this.installLocation.setPreferredSize(new Dimension(300, 26));
			// For client use the default installation location
			this.installLocation.setText(OsPaths.getDefaultInstallationDir().toString());

			row4.add(this.selectInstallationLocation = new JButton());
			this.selectInstallationLocation.setText("...");
			this.selectInstallationLocation.addActionListener(e -> {
				@Nullable
				String newLocation = displayFileChooser(this.installLocation.getText());

				if (newLocation != null) {
					this.installLocation.setText(newLocation);
				}
			});
		}

		// Profile options (Client only)
		{
			JComponent row5 = this.addRow();

			JCheckBox generateProfileBox;
			row5.add(generateProfileBox = new JCheckBox(Localization.get("gui.client.generate-profile"), null, true));
			generateProfileBox.addItemListener(e -> {
				this.generateProfile = e.getStateChange() == ItemEvent.SELECTED;
			});
			this.generateProfile = true;
			this.generateProfileCheckBox = generateProfileBox;
		}

		// Install button
		{
			JComponent row6 = this.addRow();

			row6.add(this.installButton = new JButton());
			this.installButton.setEnabled(false);
			this.installButton.setText(Localization.get("gui.install.loading"));
			this.installButton.addActionListener(this::install);
		}

		// launcher type action handling
		{
			this.launcherTypeSelector.addItemListener(item -> {
				switch (this.launcherType()) {
				case OFFICIAL:
					this.generateProfileCheckBox.setVisible(true);
					this.installLocation.setText(OsPaths.getDefaultInstallationDir().toString());
					this.installButton.setText(Localization.get("gui.install"));
					break;
				case MULTIMC:
					this.generateProfileCheckBox.setVisible(false);
					this.installButton.setText(Localization.get("gui.install.mmc"));
					this.installLocation.setText(System.getProperty("user.dir"));
					break;
				default:
					throw new RuntimeException("don't know what to do with launcher type " + item);
				}
			});
		}
	}



	private void install(ActionEvent event) {
		String minecraftVersion = (String) this.minecraftVersionSelector.getSelectedItem();
		String loaderVersion = (String) this.loaderVersionSelector.getSelectedItem();
		LauncherType launcherType = this.launcherType();
		LoaderType loaderType = this.loaderType();
		VersionManifest.Version version = this.manifest().getVersion(minecraftVersion);

		Action<InstallClient.MessageType> action = Action.installClient(
				minecraftVersion,
				launcherType,
				loaderType,
				loaderVersion,
				this.intermediaryVersions().get(version.id(GameSide.CLIENT)),
				this.installLocation.getText(),
				this.generateProfile
		);

		action.run(this);

		if (launcherType == LauncherType.MULTIMC) {
			showMmcPackGenerationMessage(loaderType);
		} else {
			showInstalledMessage(loaderType);
		}
	}

	private static void showMmcPackGenerationMessage(LoaderType type){
		showPopup(Localization.get("dialog.install.mmc.successful"), Localization.createFrom("dialog.install.mmc.successful.description", type.getLocalizedName(), "https://modrinth.com/mod/osl"),
				JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE);
	}

	LauncherType launcherType() {
		return ((LauncherLabel) this.launcherTypeSelector.getSelectedItem()).type;
	}

	@Override
	LoaderType loaderType() {
		return ((LoaderLabel) this.loaderTypeSelector.getSelectedItem()).type;
	}

	@Override
	void receiveVersions(VersionManifest manifest, Map<LoaderType, List<String>> loaderVersions, Map<String, String> intermediaryVersions) {
		super.receiveVersions(manifest, loaderVersions, intermediaryVersions);

		populateMinecraftVersions(GameSide.CLIENT, this.minecraftVersionSelector, manifest, intermediaryVersions, this.showSnapshots);
		this.showSnapshotsCheckBox.setEnabled(true);
		populateLoaderVersions(GameSide.CLIENT, this.loaderVersionSelector, this.loaderVersions(this.loaderType()), this.showLoaderBetas);
		this.showLoaderBetasCheckBox.setEnabled(true);

		this.installButton.setText(Localization.get("gui.install"));
		this.installButton.setEnabled(true);
	}

	@Override
	public void accept(InstallClient.MessageType messageType) {
	}

	class LauncherLabel extends JLabel {

		public final LauncherType type;

		public LauncherLabel(LauncherType type) {
			super(type.getLocalizedName());
			this.type = type;
		}

		// needed for it to render correctly
		@Override
		public String toString() {
			return this.getText();
		}
	}
}
