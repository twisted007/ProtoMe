package com.protome;

import burp.api.montoya.MontoyaApi;
import com.github.os72.protocjar.Protoc;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.util.JsonFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProtoManager {
    private MontoyaApi api;
    private Map<String, Descriptors.Descriptor> messageDescriptors = new HashMap<>();

    public ProtoManager(MontoyaApi api) {
        this.api = api;
    }

    public void loadProto(File userProtoFile) throws Exception {
        api.logging().logToOutput("Attempting to load proto file: " + userProtoFile.getAbsolutePath());

        File parentDir = userProtoFile.getParentFile();
        File descFile = File.createTempFile("protome", ".desc");

        // --- 1. COMPILE ---
        String[] args = {
                "-v3.11.4",
                "--include_imports",  // Crucial: Includes imported files in the set
                "--include_std_types",
                "--descriptor_set_out=" + descFile.getAbsolutePath(),
                "--proto_path=" + parentDir.getAbsolutePath(),
                userProtoFile.getName()
        };

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);

        api.logging().logToOutput("Running protoc...");
        int exitCode = Protoc.runProtoc(args, printStream, printStream);

        String protocOutput = outputStream.toString();
        if (!protocOutput.isEmpty()) {
            api.logging().logToOutput("--- PROTOC OUTPUT ---\n" + protocOutput);
        }

        if (exitCode != 0) {
            throw new RuntimeException("Protoc compilation failed. See output above.");
        }

        // --- 2. PARSE DESCRIPTORS ---
        api.logging().logToOutput("Parsing descriptors...");
        messageDescriptors.clear();

        try (FileInputStream fis = new FileInputStream(descFile)) {
            DescriptorProtos.FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.parseFrom(fis);

            // Map to hold built file descriptors to resolve dependencies
            Map<String, Descriptors.FileDescriptor> fileCache = new HashMap<>();

            // Iterate over ALL files in the set (includes imports)
            for (DescriptorProtos.FileDescriptorProto fdp : set.getFileList()) {

                // Resolve dependencies for this specific file
                List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
                for (String depName : fdp.getDependencyList()) {
                    if (fileCache.containsKey(depName)) {
                        dependencies.add(fileCache.get(depName));
                    } else {
                        api.logging().logToOutput("WARNING: Could not find dependency '" + depName + "' for " + fdp.getName());
                    }
                }

                // Convert list to array
                Descriptors.FileDescriptor[] depArray = dependencies.toArray(new Descriptors.FileDescriptor[0]);

                // Build the file descriptor using the resolved dependencies
                Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(fdp, depArray);

                // Cache it for future files that might import it
                fileCache.put(fd.getName(), fd);

                // Register all messages in this file
                for (Descriptors.Descriptor msgType : fd.getMessageTypes()) {
                    registerMessage(msgType);
                }
            }
        } catch (Throwable t) {
            // CATCH EVERYTHING so it doesn't fail silently
            api.logging().logToError(t); // Prints to Errors tab
            api.logging().logToOutput("CRITICAL ERROR PARSING DESCRIPTORS: " + t.getMessage());
            t.printStackTrace(); // Prints to system console (optional)
            throw new RuntimeException("Failed to parse descriptors: " + t.getMessage());
        }

        api.logging().logToOutput("Total messages loaded: " + messageDescriptors.size());
    }

    private void registerMessage(Descriptors.Descriptor msgType) {
        api.logging().logToOutput(">> REGISTERED: " + msgType.getFullName());
        messageDescriptors.put(msgType.getName(), msgType);
        messageDescriptors.put(msgType.getFullName(), msgType);

        for (Descriptors.Descriptor nested : msgType.getNestedTypes()) {
            registerMessage(nested);
        }
    }

    public byte[] jsonToProto(String json, String messageTypeName) throws Exception {
        if (!messageDescriptors.containsKey(messageTypeName)) {
            String availableKeys = String.join(", ", messageDescriptors.keySet());
            throw new IllegalArgumentException("Unknown message type: " + messageTypeName + ". Available: [" + availableKeys + "]");
        }

        Descriptors.Descriptor descriptor = messageDescriptors.get(messageTypeName);
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
        return builder.build().toByteArray();
    }
}
