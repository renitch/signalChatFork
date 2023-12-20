package org.asamk.signal.quartz;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.AttachmentInvalidException;
import org.asamk.signal.manager.api.Message;
import org.asamk.signal.manager.storage.groups.GroupInfo;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.util.CommandUtil;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.argparse4j.inf.Namespace;

import static org.asamk.signal.util.SendMessageResultUtils.outputResult;

public class SendImagesJob implements Job {
	
	private static final Logger logger = LoggerFactory.getLogger(SendImagesJob.class);
	
	private Namespace ns;
	private Manager m;
	private OutputWriter outputWriter;
	
	public void execute(JobExecutionContext context) throws JobExecutionException {
		JobDataMap data = context.getJobDetail().getJobDataMap();
	    ns = (Namespace) data.get("ns");
	    m = (Manager) data.get("m");
	    outputWriter = (OutputWriter) data.get("outputWriter");
		
		System.out.println("Quartz job " + java.time.ZonedDateTime.now() + " started...");
		processImages();
	}
	
    private void processImages() {
    	
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
            	throw new JobExecutionException("GroupName is empty.");
            }
            
            sourceFolderName = prop.getProperty("sourceFolderName");
            Path sourceFolderPath = new File(sourceFolderName).toPath();
            if (!Files.exists(sourceFolderPath) || !Files.isDirectory(sourceFolderPath)) {
            	logger.error("Directory \"" + sourceFolderPath + "\" does not exist or not a directory.");
            	throw new JobExecutionException("Directory \"" + sourceFolderPath + "\" does not exist or not a directory.");
            }

            try {
            	imageSendingTimeout = Integer.valueOf(prop.getProperty("timeout"));
            } catch (Exception e) {
            	logger.error("Timeout \"" + imageSendingTimeout + "\" has incorrect value.");
            	throw new JobExecutionException("Timeout \"" + imageSendingTimeout + "\" has incorrect value.");
            }
            
            if (imageSendingTimeout < 1000) {
            	logger.error("Timeout \"" + imageSendingTimeout + "\" should be at least 1000 milliseconds.");
            	throw new JobExecutionException("Timeout \"" + imageSendingTimeout + "\" should be at least 1000 milliseconds.");
            }
            
            System.out.println(groupName);
            System.out.println(sourceFolderName);
            System.out.println(imageSendingTimeout);
			
            groupId = getGroupIdByName(m, groupName);
            if (StringUtils.isEmpty(groupId)) {
            	logger.error("groupId for group with name \"" + groupName + "\" is not found.");
            	throw new JobExecutionException("groupId for group with name \"" + groupName + "\" is not found.");
            }
            logger.info("Found group with id: " + groupId);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Set<String> foundFiles = Stream.of(new File(sourceFolderName).listFiles())
					.filter(file -> !file.isDirectory())
					.map(File::getAbsolutePath)
					.collect(Collectors.toSet());

        try {
        	synchronized (ns) {
        		for (String filePath : foundFiles) {
        			sendFileToGroup(m, outputWriter, groupId, filePath);
        			Thread.sleep(imageSendingTimeout);
        		}
        	}
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

    	if (StringUtils.isEmpty(groupId) || StringUtils.isEmpty(filePath)) {
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