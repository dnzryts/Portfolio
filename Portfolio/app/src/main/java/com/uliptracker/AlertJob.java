package com.uliptracker;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic background job: refresh prices, then post alerts for big moves. */
public class AlertJob extends JobService {
    private Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                try {
                    Store store = new Store(AlertJob.this);
                    // background auto-refresh only (no alerts to check) can be limited to market hours
                    if (!Alerts.needAlerts(AlertJob.this) && Alerts.autoMarketOnly(AlertJob.this) && !Alerts.marketWindow(store.refreshedAt)) {
                        jobFinished(params, false);
                        return;
                    }
                    int[] r = Updater.run(store, Updater.online(store, null), null);
                    retry = r[0] == 0 && r[1] > 0;
                    Alerts.check(AlertJob.this, store);
                } catch (Throwable t) {
                    Crash.save(AlertJob.this, t);
                    retry = true;
                }
                jobFinished(params, retry);
            }
        });
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
