package org.asamk.signal.commands;

import static org.asamk.signal.util.SendMessageResultUtils.outputResult;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.asamk.signal.OutputType;
import org.asamk.signal.ReceiveMessageHandler;
import org.asamk.signal.Shutdown;
import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.json.JsonReceiveMessageHandler;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.AlreadyReceivingException;
import org.asamk.signal.manager.api.AttachmentInvalidException;
import org.asamk.signal.manager.api.Message;
import org.asamk.signal.manager.api.ReceiveConfig;
import org.asamk.signal.manager.storage.groups.GroupInfo;
import org.asamk.signal.output.JsonWriter;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.output.PlainTextWriter;
import org.asamk.signal.util.CommandUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

public class ReceiveCommand implements LocalCommand, JsonRpcSingleCommand<ReceiveCommand.ReceiveParams> {

    private static final Logger logger = LoggerFactory.getLogger(ReceiveCommand.class);
    private boolean isInterrupted = false;
    private Set<String> lockedFiles = new HashSet<>();

    @Override
    public String getName() {
        return "receive";
    }

    @Override
    public void attachToSubparser(final Subparser subparser) {
        subparser.help("Query the server for new messages.");
        subparser.addArgument("-t", "--timeout")
                .type(double.class)
                .setDefault(3.0)
                .help("Number of seconds to wait for new messages (negative values disable timeout)");
        subparser.addArgument("--max-messages")
                .type(int.class)
                .setDefault(-1)
                .help("Maximum number of messages to receive, before returning.");
        subparser.addArgument("--ignore-attachments")
                .help("Don’t download attachments of received messages.")
                .action(Arguments.storeTrue());
        subparser.addArgument("--ignore-stories")
                .help("Don’t receive story messages from the server.")
                .action(Arguments.storeTrue());
        subparser.addArgument("--send-read-receipts")
                .help("Send read receipts for all incoming data messages (in addition to the default delivery receipts)")
                .action(Arguments.storeTrue());
    }

    @Override
    public List<OutputType> getSupportedOutputTypes() {
        return List.of(OutputType.PLAIN_TEXT, OutputType.JSON);
    }

    @Override
    public void handleCommand(
            final Namespace ns, final Manager m, final OutputWriter outputWriter
    ) throws CommandException {
        Shutdown.installHandler();
        final var timeout = ns.getDouble("timeout");
        final var maxMessagesRaw = ns.getInt("max-messages");
        final var ignoreAttachments = Boolean.TRUE.equals(ns.getBoolean("ignore-attachments"));
        final var ignoreStories = Boolean.TRUE.equals(ns.getBoolean("ignore-stories"));
        final var sendReadReceipts = Boolean.TRUE.equals(ns.getBoolean("send-read-receipts"));
        m.setReceiveConfig(new ReceiveConfig(ignoreAttachments, ignoreStories, sendReadReceipts));

        isInterrupted = false;
        sun.misc.Signal.handle(new sun.misc.Signal("INT"),  // SIGINT
        	    signal -> {
        	    	System.out.println("Interrupted by Ctrl+C");
        	    	isInterrupted = true;
        	    }
        );

        while(!isInterrupted) {
            try {
                final var handler = switch (outputWriter) {
                    case JsonWriter writer -> new JsonReceiveMessageHandler(m, writer);
                    case PlainTextWriter writer -> new ReceiveMessageHandler(m, writer);
                };
                final var duration = timeout < 0 ? null : Duration.ofMillis((long) (timeout * 1000));
                final var maxMessages = maxMessagesRaw < 0 ? null : maxMessagesRaw;
                Shutdown.registerShutdownListener(m::stopReceiveMessages);
                System.out.println("Receiving...");
                m.receiveMessages(Optional.ofNullable(duration), Optional.ofNullable(maxMessages), handler);
                Thread.sleep(1000);
            } catch (IOException e) {
                throw new IOErrorException("Error while receiving messages: " + e.getMessage(), e);
            } catch (AlreadyReceivingException e) {
                throw new UserErrorException("Receive command cannot be used if messages are already being received.", e);
            } catch (InterruptedException e) {
            	
            }
            
            processImages(m, outputWriter);
        }
    }

    @Override
    public TypeReference<ReceiveParams> getRequestType() {
        return new TypeReference<>() {};
    }

    @Override
    public void handleCommand(
            final ReceiveParams request, final Manager m, final JsonWriter jsonWriter
    ) throws CommandException {
        final var timeout = request.timeout() == null ? 3.0 : request.timeout();
        final var maxMessagesRaw = request.maxMessages() == null ? -1 : request.maxMessages();

        try {
            final var messages = new ArrayList<>();
            final var handler = new JsonReceiveMessageHandler(m, messages::add);
            final var duration = timeout < 0 ? null : Duration.ofMillis((long) (timeout * 1000));
            final var maxMessages = maxMessagesRaw < 0 ? null : maxMessagesRaw;
            m.receiveMessages(Optional.ofNullable(duration), Optional.ofNullable(maxMessages), handler);
            jsonWriter.write(messages);
        } catch (IOException e) {
            throw new IOErrorException("Error while receiving messages: " + e.getMessage(), e);
        } catch (AlreadyReceivingException e) {
            throw new UserErrorException("Receive command cannot be used if messages are already being received.", e);
        }
    }

    public record ReceiveParams(Double timeout, Integer maxMessages) {}

    private void processImages(final Manager m, final OutputWriter outputWriter) {
    	
    	List<GroupInfo> allGroups = m.getAccount().getGroupStore().getGroups();
        Properties prop = new Properties();
        String groupName = null;
        String groupId = null;
        String sourceFolderName = null;
        int imageSendingTimeout = 1000;
        try {
            prop.load(new FileReader("configuration.properties"));
            
            groupName = prop.getProperty("groupName");
            if (StringUtils.isEmpty(groupName)) {
            	logger.error("GroupName is empty.");
            	isInterrupted = true;
            	return;
            }
            
            sourceFolderName = prop.getProperty("sourceFolderName");
            Path sourceFolderPath = new File(sourceFolderName).toPath();
            if (!Files.exists(sourceFolderPath) || !Files.isDirectory(sourceFolderPath)) {
            	logger.error("Directory \"" + sourceFolderPath + "\" does not exist or not a directory.");
            	isInterrupted = true;
            	return;
            }

            try {
            	imageSendingTimeout = Integer.valueOf(prop.getProperty("timeout"));
            } catch (Exception e) {
            	logger.error("Timeout \"" + imageSendingTimeout + "\" has incorrect value.");
            	isInterrupted = true;
            	return;            	
            }
            
            if (imageSendingTimeout < 1000) {
            	logger.error("Timeout \"" + imageSendingTimeout + "\" should be at least 1000 milliseconds.");
            	isInterrupted = true;
            	return;            	
            }
            
            System.out.println(groupName);
            System.out.println(sourceFolderName);
            System.out.println(imageSendingTimeout);
			
            groupId = getGroupIdByName(m, groupName);
            if (StringUtils.isEmpty(groupId)) {
            	logger.error("groupId for group with name \"" + groupName + "\" is not found.");
            	isInterrupted = true;
            	return;
            }
            logger.info("Found group with id: " + groupId);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Optional<String> firstFoundFile = Stream.of(new File(sourceFolderName).listFiles())
					.filter(file -> !file.isDirectory())
					.map(File::getAbsolutePath)
					.findFirst();

        try {
        	sendFileToGroup(m, outputWriter, groupId, firstFoundFile.isPresent() ? firstFoundFile.get() : null);
        	Thread.sleep(imageSendingTimeout);
        } catch (CommandException | InterruptedException e) {
        	e.printStackTrace();
        }
    }
    
    private String getGroupIdByName(final Manager m, final String groupName) {
        
    	String groupId = null;
    	List<GroupInfo> allGroups = m.getAccount().getGroupStore().getGroups();
		for (GroupInfo group : allGroups) {
			//System.out.println(group.getTitle() + " - " + group.getGroupId().toBase64());
			try {
				//System.out.println("Found: " + m.getAccount().getGroupStore().getGroup(CommandUtil.getGroupId(group.getGroupId().toBase64())).getTitle());
				if (groupName.equals(group.getTitle())) {
					groupId = group.getGroupId().toBase64();
					logger.info("Found group with name \"" + groupName + "\"");
					break;
				}
			} catch (Exception e) {
				logger.error("Failed to get group ID. " + e);
			}
		}
		
		return groupId;
    }
    
    private void sendFileToGroup(final Manager m, final OutputWriter outputWriter,
    							 final String groupId, final String filePath) throws CommandException {

    	if (StringUtils.isEmpty(groupId) || StringUtils.isEmpty(filePath) || lockedFiles.contains(filePath)) {
    		return;
    	}
    	
        final var recipientIdentifiers = CommandUtil.getRecipientIdentifiers(m,
                false,
                List.of(),
                List.of(groupId),
                List.of());

        try {
			File obj1 = File.createTempFile("temp", ".jpg");
			Files.copy(Paths.get(filePath), Paths.get(obj1.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
			logger.info("Temporary file: " + obj1.getAbsolutePath());

            final var message = new Message("",
                    List.of(obj1.getAbsolutePath()),
                    List.of(),
                    Optional.ofNullable(null),
                    Optional.ofNullable(null),
                    List.of(),
                    Optional.ofNullable(null),
                    List.of());
            logger.info("Sending message...");
            var results = m.sendMessage(message, recipientIdentifiers);
            logger.info("Message sent...");
            outputResult(outputWriter, results);
            logger.info("Deleting the file " + filePath);
            Files.delete(Paths.get(filePath));
            logger.info("File \"" + filePath + "\" sent...");
        } catch (AttachmentInvalidException e) {
        	logger.error("Failed to send message: " + e.getMessage() + " (" + e.getClass().getSimpleName() + ")", e);
        } catch (Exception e) {
        	e.printStackTrace();
        	logger.error(e.getMessage());
        }
	}
}