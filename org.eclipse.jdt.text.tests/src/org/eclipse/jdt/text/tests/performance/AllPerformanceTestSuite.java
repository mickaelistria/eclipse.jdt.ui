/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.text.tests.performance;

import junit.framework.Test;
import junit.framework.TestSuite;


/**
 * @since 3.1
 */
public class AllPerformanceTestSuite extends TestSuite {

	public static Test suite() {
		return new AllPerformanceTestSuite();
	}
	
	public AllPerformanceTestSuite() {
		// The Debug performance tests must be run separately in debug mode
		if (true) {
			addTest(PerformanceTestSuite.suite());
			addTest(PerformanceTestSuite2.suite());
			addTest(PerformanceTestSuite3.suite());
			addTest(PerformanceTestSuite4.suite());
			addTest(PerformanceTestSuite5.suite());
			addTest(EventDrivenTestSuite.suite());
		} else
			addTest(DebuggingPerformanceTestSuite.suite());
	}
}