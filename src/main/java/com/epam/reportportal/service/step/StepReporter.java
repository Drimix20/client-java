/*
 * Copyright 2020 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.reportportal.service.step;

import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.message.TypeAwareByteSource;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.utils.files.Location;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import com.google.common.base.Function;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import io.reactivex.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.Optional.ofNullable;

/**
 * A class for manual nested step reporting.
 *
 * Usage:
 * <code>
 *	StepReporter stepReporter = Launch.currentLaunch().getStepReporter();
 *	stepReporter.sendStep("My step name");
 *  // step actions
 *	stepReporter.sendStep(ItemStatus.FAILED, "My failure step name", new File("screenshot/fail.jpg"));
 * </code>
 */
public class StepReporter {

	private static final Logger LOGGER = LoggerFactory.getLogger(StepReporter.class);

	private static StepReporter instance;

	private final ThreadLocal<Maybe<String>> parent;

	private final ThreadLocal<Deque<StepEntry>> steps;

	private final ThreadLocal<Set<Maybe<String>>> parentFailures;

	private final Launch launch;

	public StepReporter(Launch currentLaunch) {
		launch = currentLaunch;
		parent = new InheritableThreadLocal<Maybe<String>>() {
			@Override
			protected Maybe<String> initialValue() {
				return Maybe.empty();
			}
		};
		steps = new InheritableThreadLocal<Deque<StepEntry>>() {

			@Override
			protected Deque<StepEntry> initialValue() {
				return Queues.newArrayDeque();
			}
		};
		parentFailures = ThreadLocal.withInitial(Sets::newHashSet);
	}

	private static class StepEntry {
		private final Maybe<String> itemId;
		private final Date timestamp;
		private final FinishTestItemRQ finishTestItemRQ;

		private StepEntry(Maybe<String> itemId, Date timestamp, FinishTestItemRQ finishTestItemRQ) {
			this.itemId = itemId;
			this.timestamp = timestamp;
			this.finishTestItemRQ = finishTestItemRQ;
		}

		public Maybe<String> getItemId() {
			return itemId;
		}

		public Date getTimestamp() {
			return timestamp;
		}

		public FinishTestItemRQ getFinishTestItemRQ() {
			return finishTestItemRQ;
		}
	}

	public void setParent(final Maybe<String> parent) {
		if (parent != null) {
			this.parent.set(parent);
		}
	}

	public Maybe<String> removeParent() {
		Maybe<String> parent = this.parent.get();
		this.parent.set(Maybe.empty());
		return parent;
	}

	public boolean isParentFailed(final Maybe<String> parentId) {
		if (parentFailures.get().contains(parentId)) {
			parentFailures.get().remove(parentId);
			return true;
		}
		return false;
	}

	private void sendStep(final ItemStatus status, final String name, final Runnable actions) {
		StartTestItemRQ rq = buildStartStepRequest(name);
		Maybe<String> stepId = startStepRequest(rq);
		if (actions != null) {
			actions.run();
		}
		finishStepRequest(stepId, status, rq.getStartTime());
	}

	public void sendStep(final String name) {
		sendStep(ItemStatus.PASSED, name, () -> {
		});
	}

	public void sendStep(@NotNull final ItemStatus status, final String name) {
		sendStep(status, name, () -> {
		});
	}

	public void sendStep(final @NotNull ItemStatus status, final String name, final Throwable throwable) {
		sendStep(
				status,
				name,
				() -> ReportPortal.emitLog((Function<String, SaveLogRQ>) itemId -> buildSaveLogRequest(itemId, LogLevel.ERROR, throwable))
		);
	}

	public void sendStep(final String name, final File... files) {
		sendStep(ItemStatus.PASSED, name, files);
	}

	public void sendStep(final @NotNull ItemStatus status, final String name, final File... files) {
		sendStep(status, name, () -> {
			for (final File file : files) {
				ReportPortal.emitLog((Function<String, SaveLogRQ>) itemId -> buildSaveLogRequest(
						itemId,
						file.getName(),
						LogLevel.INFO,
						file
				));
			}
		});
	}

	public void sendStep(final @NotNull ItemStatus status, final String name, final Throwable throwable, final File... files) {
		sendStep(status, name, () -> {
			for (final File file : files) {
				ReportPortal.emitLog((Function<String, SaveLogRQ>) itemId -> buildSaveLogRequest(itemId, LogLevel.ERROR, throwable, file));
			}
		});
	}

	public Optional<StepEntry> finishPreviousStep() {
		return ofNullable(steps.get().poll()).map(stepEntry -> {
			launch.finishTestItem(stepEntry.getItemId(), stepEntry.getFinishTestItemRQ());
			return stepEntry;
		});
	}

	private Maybe<String> startStepRequest(final StartTestItemRQ startTestItemRQ) {
		finishPreviousStep().ifPresent(e -> {
			Date previousDate = e.getTimestamp();
			Date currentDate = startTestItemRQ.getStartTime();
			if (!previousDate.before(currentDate)) {
				startTestItemRQ.setStartTime(new Date(previousDate.getTime() + 1));
			}
		});
		return launch.startTestItem(parent.get(), startTestItemRQ);
	}

	private StartTestItemRQ buildStartStepRequest(String name) {
		StartTestItemRQ startTestItemRQ = new StartTestItemRQ();
		startTestItemRQ.setName(name);
		startTestItemRQ.setType("STEP");
		startTestItemRQ.setHasStats(false);
		startTestItemRQ.setStartTime(Calendar.getInstance().getTime());
		return startTestItemRQ;
	}

	private void finishStepRequest(Maybe<String> stepId, ItemStatus status, Date timestamp) {
		FinishTestItemRQ finishTestItemRQ = buildFinishTestItemRequest(status, Calendar.getInstance().getTime());
		steps.get().add(new StepEntry(stepId, timestamp, finishTestItemRQ));
		if (ItemStatus.FAILED == status) {
			parentFailures.get().add(parent.get());
		}
	}

	private FinishTestItemRQ buildFinishTestItemRequest(ItemStatus status, Date endTime) {
		FinishTestItemRQ finishTestItemRQ = new FinishTestItemRQ();
		finishTestItemRQ.setStatus(status.name());
		finishTestItemRQ.setEndTime(endTime);
		return finishTestItemRQ;
	}

	private SaveLogRQ buildSaveLogRequest(String itemId, String message, LogLevel level) {
		SaveLogRQ rq = new SaveLogRQ();
		rq.setItemUuid(itemId);
		rq.setMessage(message);
		rq.setLevel(level.name());
		rq.setLogTime(Calendar.getInstance().getTime());
		return rq;
	}

	private SaveLogRQ buildSaveLogRequest(String itemId, String message, LogLevel level, File file) {
		SaveLogRQ logRQ = buildSaveLogRequest(itemId, message, level);
		if (file != null) {
			try {
				logRQ.setFile(createFileModel(file));
			} catch (IOException e) {
				LOGGER.error("Unable to read file attachment: " + e.getMessage(), e);
			}
		}
		return logRQ;
	}

	private SaveLogRQ buildSaveLogRequest(String itemId, LogLevel level, Throwable throwable, File file) {
		String message = throwable != null ? getStackTraceAsString(throwable) : "Test has failed without exception";
		return buildSaveLogRequest(itemId, message, level, file);
	}

	private SaveLogRQ buildSaveLogRequest(String itemId, LogLevel level, Throwable throwable) {
		return buildSaveLogRequest(itemId, level, throwable, null);
	}

	private SaveLogRQ.File createFileModel(File file) throws IOException {
		TypeAwareByteSource dataSource = Location.locateFile(file);
		SaveLogRQ.File fileModel = new SaveLogRQ.File();
		fileModel.setContent(dataSource.read());
		fileModel.setContentType(dataSource.getMediaType());
		fileModel.setName(UUID.randomUUID().toString());
		return fileModel;
	}
}
