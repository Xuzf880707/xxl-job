package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import com.xxl.job.admin.core.trigger.XxlJobTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * job trigger thread pool helper
 *
 * @author xuxueli 2018-07-03 21:08:07
 */
public class JobTriggerPoolHelper {
    private static Logger logger = LoggerFactory.getLogger(JobTriggerPoolHelper.class);


    // ---------------------- trigger pool ----------------------
    /***
     * 初始化可变大小的线程池：
     * 快任务的线程池：fastTriggerPool
     *
     * 满任务的线程池：slowTriggerPool
     */
    // fast/slow thread pool
    private ThreadPoolExecutor fastTriggerPool = new ThreadPoolExecutor(
            50,
            200,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(1000),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "xxl-job, admin JobTriggerPoolHelper-fastTriggerPool-" + r.hashCode());
                }
            });

    private ThreadPoolExecutor slowTriggerPool = new ThreadPoolExecutor(
            10,
            100,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(2000),
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "xxl-job, admin JobTriggerPoolHelper-slowTriggerPool-" + r.hashCode());
                }
            });


    // job timeout count
    private volatile long minTim = System.currentTimeMillis()/60000;     // ms > min
    /***
     * 维护jobinfo 慢执行的次数：
     *  key: jobInfo.id
     *  value：count
     */
    private volatile Map<Integer, AtomicInteger> jobTimeoutCountMap = new ConcurrentHashMap<>();

    /***
     * 添加一个触发任务
     * @param jobId
     * @param triggerType
     * @param failRetryCount
     * @param executorShardingParam
     * @param executorParam
     */
    public void addTrigger(final int jobId, final TriggerTypeEnum triggerType, final int failRetryCount, final String executorShardingParam, final String executorParam) {

        // 默认选择快的线程池
        ThreadPoolExecutor triggerPool_ = fastTriggerPool;
        //检查当前jobInfo已超时的次数(这里的超时是指执行时间>500)
        AtomicInteger jobTimeoutCount = jobTimeoutCountMap.get(jobId);
        //如果一分钟内，超时的次数>10，则采用慢线程池
        if (jobTimeoutCount!=null && jobTimeoutCount.get() > 10) {      // job-timeout 10 times in 1 min
            triggerPool_ = slowTriggerPool;
        }

        // trigger
        triggerPool_.execute(new Runnable() {
            @Override
            public void run() {
                //记录任务开始执行的时间
                long start = System.currentTimeMillis();

                try {
                    // 触发执行器执行任务
                    XxlJobTrigger.trigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {

                    //
                    long minTim_now = System.currentTimeMillis()/60000;
                    //如果当前响应的时间点和上一次标记的不在同一分钟内，则重新标记统计超时次数的时间minTim，并清空之前的统计
                    //所以这边对超时次数的统计是每隔一分钟清空一次
                    if (minTim != minTim_now) {
                        minTim = minTim_now;
                        jobTimeoutCountMap.clear();
                    }

                    // 如果当前任务执行耗时超过500ms，则记录这次慢查询
                    long cost = System.currentTimeMillis()-start;
                    if (cost > 500) {       // ob-timeout threshold 500ms
                        AtomicInteger timeoutCount = jobTimeoutCountMap.put(jobId, new AtomicInteger(1));
                        if (timeoutCount != null) {
                            timeoutCount.incrementAndGet();
                        }
                    }

                }

            }
        });
    }

    /***
     * 触发任务停止
     */
    public void stop() {
        //triggerPool.shutdown();
        fastTriggerPool.shutdownNow();
        slowTriggerPool.shutdownNow();
        logger.info(">>>>>>>>> xxl-job trigger thread pool shutdown success.");
    }

    // ---------------------- helper ----------------------
    //任务触发器工具类
    private static JobTriggerPoolHelper helper = new JobTriggerPoolHelper();

    /**
     * @param jobId jobInfo的主键
     * @param triggerType
     * @param failRetryCount
     * 			>=0: use this param
     * 			<0: use param from job info config
     * @param executorShardingParam
     * @param executorParam
     *          null: use job param
     *          not null: cover job param
     */
    public static void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, String executorShardingParam, String executorParam) {
        helper.addTrigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam);
    }

    /***
     * 停止触发的任务
     */
    public static void toStop() {
        helper.stop();
    }

}
