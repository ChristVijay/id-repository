package io.mosip.idrepository.vid.batch.config;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import javax.annotation.PostConstruct;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.mosip.idrepository.core.constant.AuditEvents;
import io.mosip.idrepository.core.constant.AuditModules;
import io.mosip.idrepository.core.constant.IdRepoErrorConstants;
import io.mosip.idrepository.core.constant.IdType;
import io.mosip.idrepository.core.exception.IdRepoAppException;
import io.mosip.idrepository.core.helper.AuditHelper;
import io.mosip.idrepository.core.logger.IdRepoLogger;
import io.mosip.idrepository.core.security.IdRepoSecurityManager;
import io.mosip.idrepository.vid.dto.NotificationResponseDTO;
import io.mosip.idrepository.vid.entity.ExpiredVid;
import io.mosip.idrepository.vid.repository.ExpiredVidRepo;
import io.mosip.idrepository.vid.service.impl.NotificationService;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;

@Component
public class VidItemTasklet implements Tasklet {

	@Value("${vid.batch.thread.count:10}")
	private int threadCount;

	@Value("${vid.batch.retry.count:5}")
	private int maxExecutionCount;

	private int executionCount;

	private int notificationSentCount;

	@Autowired
	private ExpiredVidRepo expiredVidRepo;

	/** The Constant LOGGER. */
	private static final Logger LOGGER = IdRepoLogger.getLogger(VidItemTasklet.class);
	
	private static final String EMAIL_SUCCESS = "Notification has been sent to the provided email ";

	@Autowired
	private NotificationService notificationService;

	@Autowired
	private AuditHelper audit;

	private static final String VID_ITEM_TASKLET = "VidItemTasklet";

	ForkJoinPool forkJoinPool;

	@PostConstruct
	public void init() {
		forkJoinPool = new ForkJoinPool(threadCount);
		executionCount = 0;
	}

	@Override
	public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
		String batchId = UUID.randomUUID().toString();
		LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
				"Inside VidItemTasklet.execute() method");

		LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
				"deleting previous records");
		expiredVidRepo.deleteSentVids();

		LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId, "storing expired vids");
		storeExpiredVids();

		List<ExpiredVid> vids = expiredVidRepo.findNotSentVids();
		LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
				"total records picked for sending notification: " + vids.size());

		notificationSentCount = 0;

		try {
			forkJoinPool.submit(() -> vids.parallelStream().forEach(vid -> {
				try {
					LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
							"sending notification to : " + vid.getVid());
					NotificationResponseDTO response = notificationService.sendNotification(vid.getVid(),
							vid.getIsSMSSent(), vid.getIsEmailSent(), vid.getCreatedDate());

					// if response if received(exception not thrown), even if email or sms or both
					// fails(due to invalid data only),
					// no need to retry
					if (response.getStatus() != null && response.getStatus().equalsIgnoreCase("success")) {
						LOGGER.info(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
								"notification sent successsfully");
					} else if (response.getMessage().equalsIgnoreCase(EMAIL_SUCCESS)) {
						LOGGER.error(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
								"email sent successsfully, failed to send SMS");
					} else {
						LOGGER.error(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
								"sms sent successsfully, failed to send Email");
					}
					vid.setIsSMSSent(true);
					vid.setIsEmailSent(true);
					expiredVidRepo.save(vid);
					notificationSentCount++;
				} catch (IdRepoAppException e) {
					LOGGER.error(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
							"exception while sending notification: " + ExceptionUtils.getStackTrace(e));

					// if exception thrown need to check for which error codes need to retry
					// need to check when exception thrown any notification(email or sms) is sent or
					// not
					if (e.getErrorCode().equalsIgnoreCase(IdRepoErrorConstants.NOTIFICATION_FAILURE.getErrorCode())) {
						vid.setIsSMSSent(true);
						vid.setIsEmailSent(true);
						expiredVidRepo.save(vid);
						notificationSentCount++;
					}
//					else if (e.getErrorCode().equalsIgnoreCase("")){
//						vid.setIsEmailSent(true);
//						expiredVidRepo.save(vid);
//					}
				} catch (Exception e) {
					LOGGER.error(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
							"exception while sending notification: " + ExceptionUtils.getStackTrace(e));
				}
			})).get();
		} catch (InterruptedException | ExecutionException e) {
			LOGGER.error(IdRepoSecurityManager.getUser(), VID_ITEM_TASKLET, "batchid = " + batchId,
					ExceptionUtils.getStackTrace(e));
		}

		executionCount++;
		LOGGER.info("Retry count: " + executionCount);

		if (executionCount >= maxExecutionCount || notificationSentCount == vids.size()) {
			audit.audit(AuditModules.ID_REPO_VID_SERVICE, AuditEvents.SEND_EXPIRY_NOTIFICATIONS, batchId,
					IdType.VID, "Notifications sent for expired vids, count: " + notificationSentCount);
			executionCount = 0;
			return RepeatStatus.FINISHED;
		}

		return RepeatStatus.CONTINUABLE;
	}

	public void storeExpiredVids() {
		expiredVidRepo.insertExpiredVids();
	}

}
