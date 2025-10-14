package org.itmo;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import java.util.concurrent.atomic.AtomicIntegerArray;

@JCStressTest
@State
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Первый поток пометил вершину")
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Второй поток пометил вершину")
@Outcome(id = "1, 1", expect = Expect.FORBIDDEN,   desc = "Обе пометки прошли — гонка!")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN,   desc = "Никто не пометил")
public class VisitCASRaceTest {

    private final AtomicIntegerArray dist = new AtomicIntegerArray(2);
    private final int level = 0;

    public VisitCASRaceTest() {
        dist.set(0, 0);
        dist.set(1, -1);
    }

    @Actor
    public void actor1(II_Result r) {
        int v = 1;
        boolean won = (dist.get(v) == -1) && dist.compareAndSet(v, -1, level + 1);
        r.r1 = won ? 1 : 0;
    }

    @Actor
    public void actor2(II_Result r) {
        int v = 1;
        boolean won = (dist.get(v) == -1) && dist.compareAndSet(v, -1, level + 1);
        r.r2 = won ? 1 : 0;
    }
}
