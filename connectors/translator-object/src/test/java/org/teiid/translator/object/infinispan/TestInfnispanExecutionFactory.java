/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.translator.object.infinispan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.teiid.translator.object.ObjectExecution;
import org.teiid.translator.object.ObjectExecutionFactory;
import org.teiid.translator.object.SearchByKey;
import org.teiid.translator.object.TestObjectExecutionFactory;
import org.teiid.translator.object.util.VDBUtility;

@SuppressWarnings("nls")
public class TestInfnispanExecutionFactory extends TestObjectExecutionFactory {
	

	public class TestFactory extends InfinispanExecutionFactory {
		public TestFactory() {
			
		}

	}


	@Override
	protected ObjectExecutionFactory createFactory() {
		InfinispanExecutionFactory f = new TestFactory();
		
		return f;
	}
	
	
	@Test public void testDefaultSearch() throws Exception {
		InfinispanExecutionFactory f = (InfinispanExecutionFactory) this.factory;
		factory.start();
			
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, conn);
		
		assertNotNull(exec);
		assertFalse(f.supportsLuceneSearching());
		assertFalse(f.supportsDSLSearching());
		assertFalse(f.isFullTextSearchingSupported());
		assertTrue(f.getSearchType() instanceof SearchByKey);
	}	
	
	
	@Test public void testLuceneSearchEnabled() throws Exception {
		InfinispanExecutionFactory f = (InfinispanExecutionFactory) this.factory;
		f.setSupportsLuceneSearching(true);
		factory.start();
			
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, conn);
		
		assertNotNull(exec);
		assertTrue(f.supportsLuceneSearching());
		assertTrue(f.getSearchType() instanceof LuceneSearch);
		assertTrue(f.isFullTextSearchingSupported());

	}	
	
	@Test
	public void testLuceneSearchNotEnabled() throws Exception {
		InfinispanExecutionFactory f = (InfinispanExecutionFactory) this.factory;
		f.setSupportsLuceneSearching(false);
		factory.start();
			
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, conn);
		
		assertNotNull(exec);
		assertFalse(f.supportsLuceneSearching());
		assertFalse(f.getSearchType() instanceof LuceneSearch);
		assertFalse(f.isFullTextSearchingSupported());

	}	
	
	
	@Test public void testDSLSearchEnabled() throws Exception {
		InfinispanExecutionFactory f = (InfinispanExecutionFactory) this.factory;
		f.setSupportsDSLSearching(true);
		factory.start();
			
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, conn);
		
		assertNotNull(exec);
		assertTrue(f.supportsDSLSearching());
		assertTrue(f.getSearchType() instanceof DSLSearch);
		assertTrue(f.isFullTextSearchingSupported());

	}	
	
	@Test
	public void testDSLSearchNotEnabled() throws Exception {
		InfinispanExecutionFactory f = (InfinispanExecutionFactory) this.factory;
		f.setSupportsDSLSearching(false);
		factory.start();
			
		ObjectExecution exec = (ObjectExecution) factory.createExecution(command, context, VDBUtility.RUNTIME_METADATA, conn);
		
		assertNotNull(exec);
		assertFalse(f.supportsDSLSearching());
		assertFalse(f.getSearchType() instanceof DSLSearch);
		assertFalse(f.isFullTextSearchingSupported());

	}		
	

}
