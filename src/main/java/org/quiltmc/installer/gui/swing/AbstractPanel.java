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

import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.HyperlinkEvent;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.installer.GameSide;
import org.quiltmc.installer.LoaderType;
import org.quiltmc.installer.Localization;
import org.quiltmc.installer.VersionManifest;

abstract class AbstractPanel extends JPanel {
	final SwingInstaller gui;
	@Nullable
	private VersionManifest manifest;
	@Nullable
	private Map<LoaderType, List<String>> loaderVersions;
	@Nullable
	private Map<String, String> intermediaryVersions;
	protected boolean beaconOptOut = false;

	AbstractPanel(SwingInstaller gui) {
		this.gui = gui;

		this.setLayout(new BoxLayout(this, BoxLayout.PAGE_AXIS));
	}

	JComponent addRow() {
		JPanel rowPanel = new JPanel(new FlowLayout());
		this.add(rowPanel);
		return rowPanel;
	}

	void receiveVersions(VersionManifest manifest, Map<LoaderType, List<String>> loaderVersions, Map<String, String> intermediaryVersions) {
		this.manifest = manifest;
		this.loaderVersions = loaderVersions;
		this.intermediaryVersions = intermediaryVersions;
	}

	@Nullable
	VersionManifest manifest() {
		return this.manifest;
	}

	@Nullable
	public Map<LoaderType, List<String>> loaderVersions() {
		return this.loaderVersions;
	}

	@Nullable 
	public List<String> loaderVersions(LoaderType type) {
		return this.loaderVersions == null ? null : this.loaderVersions.get(type);
	}

	@Nullable
	public Map<String, String> intermediaryVersions() {
		return this.intermediaryVersions;
	}

	abstract LoaderType loaderType();

	static void populateMinecraftVersions(GameSide side, JComboBox<String> comboBox, VersionManifest manifest, Map<String, String> intermediaryVersions, boolean snapshots) {
		// Setup the combo box for Minecraft version selection
		comboBox.removeAllItems();

		for (VersionManifest.Version version : manifest) {
			if (version.type().equals("release")
				|| (version.type().equals("snapshot") && snapshots)
				|| (version.type().equals("old_beta") && snapshots)
				|| (version.type().equals("old_alpha") && snapshots)
				|| (version.type().equals("alpha_server") && snapshots)
				|| (version.type().equals("classic_server") && snapshots)) {
				if (intermediaryVersions.containsKey(version.id(side))) {
					comboBox.addItem(version.id());
				}
			}
		}

		comboBox.setEnabled(true);
	}

	static void populateLoaderVersions(GameSide side, JComboBox<String> comboBox, List<String> loaderVersions, boolean betas) {
		comboBox.removeAllItems();

		for (String loaderVersion : loaderVersions) {
			if (betas || !loaderVersion.contains("-")) {
				comboBox.addItem(loaderVersion);
			}
		}

		comboBox.setEnabled(true);
	}

	/*protected @Nullable List<JComponent> createBeaconOptOut() {
		if (Boolean.parseBoolean(System.getenv().get("QUILT_LOADER_DISABLE_BEACON")) ||
			Boolean.parseBoolean(System.getenv().get("CI")) ||
			Boolean.getBoolean("loader.disable_beacon")) {
			// Telemetry is already disabled, likely for the whole system.
			// We shouldn't show an opt-out checkbox in order not to confuse users
			return null;
		}

		List<JComponent> components = new ArrayList<>();

		String link = "https://quiltmc.org/en/blog/2023-06-26-mau-beacon/";
		JLabel linkLabel = new JLabel(String.format("<html><a href=\"%s\">(?)</a></html>", link));
		linkLabel.setToolTipText(Localization.get("gui.beacon-opt-out.link-hover"));
		linkLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		linkLabel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				try {
					Desktop.getDesktop().browse(new URI(link));
				} catch (IOException | URISyntaxException ex) {
					throw new RuntimeException(ex);
				}
			}
		});

		JCheckBox optOutBox = new JCheckBox(Localization.get("gui.beacon-opt-out"), null, this.beaconOptOut);
		optOutBox.addItemListener(e -> {
			this.beaconOptOut = e.getStateChange() == ItemEvent.SELECTED;
		});
		optOutBox.add(linkLabel);

		components.add(optOutBox);
		components.add(linkLabel);
		return components;
	}*/

	@Nullable
	static String displayFileChooser(String initialDir) {
		JFileChooser chooser = new JFileChooser();
		chooser.setCurrentDirectory(new File(initialDir));
		chooser.setDialogTitle(Localization.get("gui.install-location.select"));
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);

		if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
			return chooser.getSelectedFile().getAbsolutePath();
		}

		return null;
	}

	/**
	 * Show a popup with hyperlinks and full html formatting.
	 * @return if the user pressed "ok", "yes", etc. (showOptionDialog returned 0)
	 */
	protected static boolean showPopup(String title, String description, int optionType, int messageType) {
		JEditorPane pane = new JEditorPane("text/html",
				"<html><body style=\"" + buildEditorPaneStyle() + "\">" + description + "</body></html>");
		pane.setEditable(false);
		pane.addHyperlinkListener(e -> {
			try {
				if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
					if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
						Desktop.getDesktop().browse(e.getURL().toURI());
					} else {
						throw new UnsupportedOperationException("Failed to open " + e.getURL().toString());
					}
				}
			} catch (Throwable throwable) {
				displayError(pane, throwable);
			}
		});
		return JOptionPane.showOptionDialog(null, pane, title, optionType, messageType, null, null, null) == 0;
	}

	protected static void showInstalledMessage(LoaderType type) {
		showPopup(Localization.get("dialog.install.successful"), Localization.createFrom("dialog.install.successful.description", type.getLocalizedName(), "https://modrinth.com/mod/osl"),
				JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE);
	}

	private static String buildEditorPaneStyle() {
		JLabel label = new JLabel();
		Font font = label.getFont();
		Color color = label.getBackground();

		return String.format(
				"font-family:%s;font-weight:%s;font-size:%dpt;background-color: rgb(%d,%d,%d);",
				font.getFamily(), (font.isBold() ? "bold" : "normal"), font.getSize(), color.getRed(), color.getGreen(), color.getBlue()
		);
	}

	static void displayError(Component parent, Throwable throwable) {
		JOptionPane.showMessageDialog(parent, throwable.toString(), "Error!", JOptionPane.ERROR_MESSAGE);
		throwable.printStackTrace();
	}

	class LoaderLabel extends JLabel {

		public final LoaderType type;

		public LoaderLabel(LoaderType type) {
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
