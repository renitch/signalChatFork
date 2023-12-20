/*
  Copyright (C) 2015-2022 AsamK and contributors

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.asamk.signal;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.IOErrorException;
import org.asamk.signal.commands.exceptions.RateLimitErrorException;
import org.asamk.signal.commands.exceptions.UnexpectedErrorException;
import org.asamk.signal.commands.exceptions.UntrustedKeyErrorException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.logging.LogConfigurator;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.ManagerLogger;
import org.asamk.signal.output.OutputWriter;
import org.asamk.signal.quartz.SendImagesJob;
import org.asamk.signal.util.SecurityProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
import org.slf4j.bridge.SLF4JBridgeHandler;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.DefaultSettings;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class Main {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	public static void main(String[] args) {
		// enable unlimited strength crypto via Policy, supported on relevant JREs
		Security.setProperty("crypto.policy", "unlimited");
		installSecurityProviderWorkaround();

		// Configuring the logger needs to happen before any logger is initialized
		final var loggingConfig = parseLoggingConfig(args);
		configureLogging(loggingConfig);

		final var parser = App.buildArgumentParser();
		final var ns = parser.parseArgsOrFail(args);

		int status = 0;
		try {
			new App(ns).init();
		} catch (CommandException e) {
			System.err.println(e.getMessage());
			if (loggingConfig.verboseLevel > 0 && e.getCause() != null) {
				e.getCause().printStackTrace(System.err);
			}
			status = getStatusForError(e);
		} catch (Throwable e) {
			e.printStackTrace(System.err);
			status = 2;
		}
		Shutdown.shutdownComplete();
		System.exit(status);
	}

	public static void initQuartz(Namespace ns, Manager m, OutputWriter outputWriter) throws SchedulerException {
		org.quartz.SchedulerFactory schedulerFactory = new StdSchedulerFactory();
		Scheduler scheduler = schedulerFactory.getScheduler();

		for (Trigger trigger : parseSchedules()) {
			JobDetail job = JobBuilder.newJob(SendImagesJob.class).withIdentity("job" + trigger.getKey(), "group1")
					.build();
			job.getJobDataMap().put("ns", ns);
			job.getJobDataMap().put("m", m);
			job.getJobDataMap().put("outputWriter", outputWriter);
			
			scheduler.scheduleJob(job, trigger);
		}
		scheduler.start();
	}

	private static List<Trigger> parseSchedules() throws SchedulerException {

		List<Trigger> triggers = new ArrayList<>();

		Properties prop = new Properties();
		String scheduleTimeString = null;
		try {
			prop.load(new FileReader("configuration.properties"));

			scheduleTimeString = prop.getProperty("scheduleTime");
			if (StringUtils.isEmpty(scheduleTimeString)) {
				logger.error("scheduleTimeString is empty.");
				throw new SchedulerException("scheduleTimeString is empty.");
			}

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
		} catch (IOException e) {
			logger.error(e.getMessage());
		}

		return triggers;
	}

	private static void installSecurityProviderWorkaround() {
		// Register our own security provider
		Security.insertProviderAt(new SecurityProvider(), 1);
		Security.addProvider(new BouncyCastleProvider());
	}

	private static LoggingConfig parseLoggingConfig(final String[] args) {
		final var nsLog = parseArgs(args);
		if (nsLog == null) {
			return new LoggingConfig(0, null, false);
		}

		final var verboseLevel = nsLog.getInt("verbose");
		final var logFile = nsLog.<File>get("log-file");
		final var scrubLog = nsLog.getBoolean("scrub-log");
		return new LoggingConfig(verboseLevel, logFile, scrubLog);
	}

	/**
	 * This method only parses commandline args relevant for logging configuration.
	 */
	private static Namespace parseArgs(String[] args) {
		var parser = ArgumentParsers.newFor("signal-cli", DefaultSettings.VERSION_0_9_0_DEFAULT_SETTINGS)
				.includeArgumentNamesAsKeysInResult(true).build().defaultHelp(false);
		parser.addArgument("-v", "--verbose").action(Arguments.count());
		parser.addArgument("--log-file").type(File.class);
		parser.addArgument("--scrub-log").action(Arguments.storeTrue());

		try {
			return parser.parseKnownArgs(args, null);
		} catch (ArgumentParserException e) {
			return null;
		}
	}

	private static void configureLogging(final LoggingConfig loggingConfig) {
		LogConfigurator.setVerboseLevel(loggingConfig.verboseLevel);
		LogConfigurator.setLogFile(loggingConfig.logFile);
		LogConfigurator.setScrubSensitiveInformation(loggingConfig.scrubLog);

		if (loggingConfig.verboseLevel > 0) {
			java.util.logging.Logger.getLogger("").setLevel(
					loggingConfig.verboseLevel > 2 ? java.util.logging.Level.FINEST : java.util.logging.Level.INFO);
			ManagerLogger.initLogger();
		}
		SLF4JBridgeHandler.removeHandlersForRootLogger();
		SLF4JBridgeHandler.install();
	}

	private static int getStatusForError(final CommandException e) {
		return switch (e) {
		case UserErrorException userErrorException -> 1;
		case UnexpectedErrorException unexpectedErrorException -> 2;
		case IOErrorException ioErrorException -> 3;
		case UntrustedKeyErrorException untrustedKeyErrorException -> 4;
		case RateLimitErrorException rateLimitErrorException -> 5;
		case null -> 2;
		};
	}

	private record LoggingConfig(int verboseLevel, File logFile, boolean scrubLog) {
	}
}
