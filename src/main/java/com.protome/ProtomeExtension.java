package com.protome;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.awt.*;
import java.io.File;

public class ProtomeExtension implements BurpExtension {
    private MontoyaApi api;
    private ProtoManager protoManager;
    private RequestLogger logger;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.api.extension().setName("Protome");
        this.protoManager = new ProtoManager(api);
        this.logger = new RequestLogger(api);

        // --- Build UI ---
        JPanel mainPanel = new JPanel(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();

        // Config Tab
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JLabel pathLabel = new JLabel("No file selected");
        JButton browseButton = new JButton("Select .proto File");

        browseButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            int result = fileChooser.showOpenDialog(mainPanel);

            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();
                pathLabel.setText(selectedFile.getAbsolutePath());

                // Trigger the load immediately
                try {
                    protoManager.loadProto(selectedFile);
                    JOptionPane.showMessageDialog(mainPanel, "Loaded successfully! Check Output tab for details.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(mainPanel, "Error: " + ex.getMessage());
                    api.logging().logToError(ex);
                }
            }
        });

        configPanel.add(browseButton);
        configPanel.add(pathLabel);

        tabs.add("Settings", configPanel);
        tabs.add("Logger", logger.getUiComponent());

        mainPanel.add(tabs);

        api.userInterface().registerSuiteTab("Protome", mainPanel);
        api.http().registerHttpHandler(new ProtomeHttpHandler(api, protoManager, logger));
        api.logging().logToOutput("Protome loaded.");
    }
}
