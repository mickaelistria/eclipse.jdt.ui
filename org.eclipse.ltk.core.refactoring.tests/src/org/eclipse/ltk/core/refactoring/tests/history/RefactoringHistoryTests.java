/*******************************************************************************
 * Copyright (c) 2006, 2013 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ltk.core.refactoring.tests.history;

import junit.framework.Test;
import junit.framework.TestSuite;

public class RefactoringHistoryTests {

	public static Test suite() {
		TestSuite suite= new TestSuite(RefactoringHistoryTests.class.getName());
		suite.addTestSuite(RefactoringHistorySerializationTests.class);
		suite.addTestSuite(RefactoringHistoryServiceTests.class);
		return suite;
	}
}