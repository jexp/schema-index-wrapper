package org.neo4j.index.wrapper;

import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.test.ImpermanentGraphDatabase;

import static org.junit.Assert.assertEquals;

/**
 * @author mh
 * @since 06.05.13
 */
public class WrapperDbIndexTest extends BasicIndexTest {
    @Override
    protected ImpermanentGraphDatabase createDatabase() {
        return new ImpermanentGraphDatabase(MapUtil.stringMap("index-wrapper.foo.bar","name:foo-bar"));
    }
}
