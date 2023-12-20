package org.asamk.signal;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.storage.groups.GroupInfo;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.quartz.SendImagesJob;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.argparse4j.inf.Namespace;

public class CustomConfig {

	private static final Logger logger = LoggerFactory.getLogger(CustomConfig.class);
	
	private static volatile CustomConfig customConfig;
	
	private String messagesGroupName = null;
	private String messagesGroupId = null;
	
	private String imagesGroupName = null;
	private String imagesGroupId = null;
	private String sourceFolderName = null;

	private String scheduleTimeString = null;
	
	private int imageSendingTimeout = 1000;

    private CustomConfig() {
	}
	
    public static CustomConfig getInstance() throws UserErrorException {
        if (customConfig == null) {
            throw new UserErrorException("Config is not initialized");
        }
        
        return customConfig;
    }
    
	public static CustomConfig getInstance(Namespace ns, Manager m, OutputWriter outputWriter) throws UserErrorException {
		if (customConfig == null) {
			customConfig = new CustomConfig();
			customConfig.init(ns, m, outputWriter);
		}
		
		return customConfig;
	}
	
	private void init(Namespace ns, Manager m, OutputWriter outputWriter) throws UserErrorException {
        Properties prop = new Properties();
        
        try {
            prop.load(new FileReader("configuration.properties"));
            
            messagesGroupName = prop.getProperty("messagesGroupName");
            if (StringUtils.isEmpty(messagesGroupName)) {
            	logger.error("MessagesGroupName is empty.");
            	throw new UserErrorException("MessagesGroupName is empty.");
            }
            messagesGroupId = getGroupIdByName(m, messagesGroupName);
            
            imagesGroupName = prop.getProperty("imagesGroupName");
            if (StringUtils.isEmpty(imagesGroupName)) {
            	logger.error("ImagesGroupName is empty.");
            	throw new UserErrorException("ImagesGroupName is empty.");
            }
            imagesGroupId = getGroupIdByName(m, imagesGroupName);

            sourceFolderName = prop.getProperty("sourceFolderName");
            Path sourceFolderPath = new File(sourceFolderName).toPath();
            if (!Files.exists(sourceFolderPath) || !Files.isDirectory(sourceFolderPath)) {
            	logger.error("Directory \"" + sourceFolderPath + "\" does not exist or not a directory.");
            	throw new UserErrorException("Directory \"" + sourceFolderPath + "\" does not exist or not a directory.");
            }

            scheduleTimeString = prop.getProperty("scheduleTime");
            if (StringUtils.isEmpty(scheduleTimeString)) {
                logger.error("scheduleTimeString is empty.");
                throw new UserErrorException("scheduleTimeString is empty.");
            }

            System.out.println(messagesGroupName + " - " + messagesGroupId);
            System.out.println(imagesGroupName + " - " + imagesGroupId);
            System.out.println(sourceFolderName);
            System.out.println(scheduleTimeString);
			
            logger.info("Found group with id: " + messagesGroupId);
            
            initQuartz(ns, m, outputWriter);
        } catch (IOException e) {
        	throw new UserErrorException("Cannot find file \"configuration.properties\"");
        }
	}
	
	public void tryGetGroupIds(final Manager m) {
        messagesGroupId = getGroupIdByName(m, messagesGroupName);
        imagesGroupId = getGroupIdByName(m, imagesGroupName);
	}
	
    public static String getGroupIdByName(final Manager m, final String groupName) {
        
        String groupId = null;
        List<GroupInfo> allGroups = m.getAccount().getGroupStore().getGroups();
        for (GroupInfo group : allGroups) {
            try {
                if (groupName.equals(group.getTitle())) {
                    groupId = group.getGroupId().toBase64();
                    logger.info("Found group with name \"" + groupName + "\"");
                    break;
                }
            } catch (Exception e) {
                logger.error("Failed to get group ID. " + e);
                //throw new UserErrorException("groupId for group with name \"" + messagesGroupName + "\" is not found.");
            }
        }
        
        return groupId;
    }
	
    private void initQuartz(Namespace ns, Manager m, OutputWriter outputWriter) throws UserErrorException {
        org.quartz.SchedulerFactory schedulerFactory = new StdSchedulerFactory();
        
        try {
        	Scheduler scheduler = schedulerFactory.getScheduler();

        	for (Trigger trigger : parseSchedules()) {
        		JobDetail job = JobBuilder.newJob(SendImagesJob.class).withIdentity("job" + trigger.getKey(), "group1").build();
        		job.getJobDataMap().put("ns", ns);
            	job.getJobDataMap().put("m", m);
            	job.getJobDataMap().put("outputWriter", outputWriter);
            
        		scheduler.scheduleJob(job, trigger);
        	}
        	scheduler.start();
        } catch (SchedulerException e) {
        	throw new UserErrorException("Error occurred while configuring job scheduler: " + e.getMessage(), e);
        }
    }

    private List<Trigger> parseSchedules() throws SchedulerException {

        List<Trigger> triggers = new ArrayList<>();

        List<String> items = Arrays.asList(scheduleTimeString.split(";"));
		for (String scheduleTime : items) {
		    Matcher matcher = Pattern.compile("^(\\d+):(\\d+)").matcher(scheduleTime);
		    if (matcher.find()) {
		        String HH = matcher.group(1);
		        String MM = matcher.group(2);

		        Trigger trigger = TriggerBuilder.newTrigger().withIdentity("triggerFor_" + HH + "_" + MM, "group1")
		                .withSchedule(CronScheduleBuilder.cronSchedule("00 " + MM + " " + HH + " * * ?")).build();
		        triggers.add(trigger);
		    } else {
		        logger.error("scheduleTime " + scheduleTime + " is not in format HH:MM");
		        throw new SchedulerException("scheduleTime " + scheduleTime + " is not in format HH:MM");
		    }
		}

        return triggers;
    }

    public String getMessagesGroupName() {
        return messagesGroupName;
    }

    public String getMessagesGroupId() {
        return messagesGroupId;
    }

    public String getImagesGroupName() {
        return imagesGroupName;
    }

    public String getImagesGroupId() {
        return imagesGroupId;
    }

    public String getSourceFolderName() {
        return sourceFolderName;
    }

    public int getImageSendingTimeout() {
        return imageSendingTimeout;
    }
}
