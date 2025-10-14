package org.itmo;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

@JCStressTest
@State
@Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "После получения маркера данные видны")
@Outcome(id = "-1", expect = Expect.ACCEPTABLE, desc = "Маркер не получен")
@Outcome(id = "0", expect = Expect.FORBIDDEN,   desc = "Маркер получен, но данных не видно")
public class BucketsPublicationViaQueueTest {

    private final ArrayList<Integer>[] buckets = new ArrayList[1];
    private final LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>();

    @Actor
    public void writer() {
        buckets[0] = new ArrayList<>();
        q.offer(1);
    }

    @Actor
    public void reader(I_Result r) {
        Integer m = q.poll();
        if (m == null) {
            r.r1 = -1;
        } else {
            r.r1 = (buckets[0] != null) ? 1 : 0;
        }
    }
}
