package com.postgresforall.core.util;

import com.postgresforall.core.model.ConnectionProfile;
import com.postgresforall.core.service.ConnectionErrors;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class CancelTokenTest {

    @Test
    void callbacksRunOnce_andLateRegistrationsRunImmediately() {
        var t = new CancelToken();
        var hits = new AtomicInteger();
        t.register(hits::incrementAndGet);
        t.cancel();
        t.cancel();
        assertThat(hits).hasValue(1);
        t.register(hits::incrementAndGet);
        assertThat(hits).hasValue(2);
        assertThatThrownBy(t::throwIfCancelled).isInstanceOf(CancelToken.CancelledException.class);
    }

    @Test
    void closedRegistration_isNotCalled() {
        var t = new CancelToken();
        var hits = new AtomicInteger();
        t.register(hits::incrementAndGet).close();
        t.cancel();
        assertThat(hits).hasValue(0);
    }

    @Test
    void linkedChild_followsParent_butNotViceVersa() {
        var parent = new CancelToken();
        var child = parent.linked();
        child.cancel();
        assertThat(parent.isCancelled()).isFalse();
        var child2 = parent.linked();
        parent.cancel();
        assertThat(child2.isCancelled()).isTrue();
    }

    @Test
    void none_cannotBeCancelled() {
        CancelToken.NONE.cancel();
        assertThat(CancelToken.NONE.isCancelled()).isFalse();
    }

    @Test
    void connectionErrors_classifiesRefusedPortAsUnreachable() {
        var p = new ConnectionProfile();
        p.host = "127.0.0.1";
        p.port = 1;
        var ex = catchThrowable(p::open);
        assertThat(ex).isNotNull();
        assertThat(ConnectionErrors.classify(ex)).isEqualTo(ConnectionErrors.Kind.UNREACHABLE);
        assertThat(ConnectionErrors.message(ex)).isNotBlank();
    }
}
