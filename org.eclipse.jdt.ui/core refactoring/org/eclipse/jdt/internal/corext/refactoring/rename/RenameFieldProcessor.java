/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.rename;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.NamingConventions;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.ISearchPattern;
import org.eclipse.jdt.core.search.SearchEngine;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.codemanipulation.GetterSetterUtil;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.CompositeChange;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringSearchEngine;
import org.eclipse.jdt.internal.corext.refactoring.SearchResult;
import org.eclipse.jdt.internal.corext.refactoring.SearchResultGroup;
import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.participants.IRenameParticipant;
import org.eclipse.jdt.internal.corext.refactoring.participants.IResourceModifications;
import org.eclipse.jdt.internal.corext.refactoring.participants.JavaProcessors;
import org.eclipse.jdt.internal.corext.refactoring.participants.RenameProcessor;
import org.eclipse.jdt.internal.corext.refactoring.tagging.IReferenceUpdating;
import org.eclipse.jdt.internal.corext.refactoring.tagging.ITextUpdating;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaElementUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.TextChangeManager;
import org.eclipse.jdt.internal.corext.textmanipulation.SimpleTextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEditCopier;
import org.eclipse.jdt.internal.corext.textmanipulation.TextRange;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.corext.util.WorkingCopyUtil;

public class RenameFieldProcessor extends RenameProcessor implements IReferenceUpdating, ITextUpdating {
	
	private static final String DECLARED_SUPERTYPE= RefactoringCoreMessages.getString("RenameFieldRefactoring.declared_in_supertype"); //$NON-NLS-1$
	private IField fField;
	private SearchResultGroup[] fReferences;
	private TextChangeManager fChangeManager;
	private ICompilationUnit[] fNewWorkingCopies;
	private boolean fUpdateReferences;
	
	private boolean fUpdateJavaDoc;
	private boolean fUpdateComments;
	private boolean fUpdateStrings;
	
	private boolean fRenameGetter;
	private boolean fRenameSetter;
	
	//---- IRefactoringProcessor --------------------------------

	public void initialize(Object field) {
		if (!(field instanceof IField))
			return;
		fField= (IField)field;
		setNewElementName(fField.getElementName());
		fUpdateReferences= true;
		fUpdateJavaDoc= false;
		fUpdateComments= false;
		fUpdateStrings= false;
		
		fRenameGetter= false;
		fRenameSetter= false;
	}
	
	public boolean isAvailable() throws CoreException {
		if (fField == null)
			return false;
		return Checks.isAvailable(fField);
	}
	
	 public String getProcessorName() {
		return RefactoringCoreMessages.getFormattedString(
			"RenameFieldRefactoring.name", //$NON-NLS-1$
			new String[]{fField.getElementName(), getNewElementName()});
	 }
	
	public IProject[] getScope() throws CoreException {
		return JavaProcessors.computeScope(fField);
	}

	public Object getElement() {
		return fField;
	}

	public Object[] getDerivedElements() throws CoreException {
		List result= new ArrayList(2);
		IMethod method= getGetter();
		if (fRenameGetter && method != null)
			result.add(method);
		method= getSetter();
		if (fRenameSetter && method != null)
			result.add(method);
		return result.toArray();
	}
	
	public IResourceModifications getResourceModifications() {
		return null;
	}
	
	public void propagateDataTo(IRenameParticipant participant) throws CoreException {
		if (participant.operatesOn(fField)) {
			participant.setNewElementName(getNewElementName());
			participant.setUpdateReferences(getUpdateReferences());
			return;
		}
		if (fRenameGetter) {
			IMethod getter= getGetter();
			if (participant.operatesOn(getter)) {
				participant.setNewElementName(getNewGetterName());
				participant.setUpdateReferences(getUpdateReferences());
			}
		}
		if (fRenameSetter) {
			IMethod setter= getSetter();
			if (participant.operatesOn(setter)) {
				participant.setNewElementName(getNewSetterName());
				participant.setUpdateReferences(getUpdateReferences());
			}
		}
	}
	
	//---- IRenameProcessor -------------------------------------
	
	public final String getCurrentElementName(){
		return fField.getElementName();
	}
	
	public RefactoringStatus checkNewElementName(String newName) throws CoreException {
		Assert.isNotNull(newName, "new name"); //$NON-NLS-1$
		RefactoringStatus result= Checks.checkFieldName(newName);
		
		if (isInstaceField(fField) && (! Checks.startsWithLowerCase(newName)))
			result.addWarning(RefactoringCoreMessages.getString("RenameFieldRefactoring.should_start_lowercase")); //$NON-NLS-1$
			
		if (Checks.isAlreadyNamed(fField, newName))
			result.addFatalError(RefactoringCoreMessages.getString("RenameFieldRefactoring.another_name")); //$NON-NLS-1$
		if (fField.getDeclaringType().getField(newName).exists())
			result.addFatalError(RefactoringCoreMessages.getString("RenameFieldRefactoring.field_already_defined")); //$NON-NLS-1$
		return result;
	}
	
	public Object getNewElement() {
		return fField.getDeclaringType().getField(fNewElementName);
	}
	
	//---- ITextUpdating ---------------------------------------------
	
	public boolean canEnableTextUpdating() {
		return true;
	}
	
	public boolean getUpdateJavaDoc() {
		return fUpdateJavaDoc;
	}

	public boolean getUpdateComments() {
		return fUpdateComments;
	}

	public boolean getUpdateStrings() {
		return fUpdateStrings;
	}

	public void setUpdateJavaDoc(boolean update) {
		fUpdateJavaDoc= update;
	}

	public void setUpdateComments(boolean update) {
		fUpdateComments= update;
	}

	public void setUpdateStrings(boolean update) {
		fUpdateStrings= update;
	}
	
	//---- IReferenceUpdating -----------------------------------

	public boolean canEnableUpdateReferences() {
		return true;
	}

	public void setUpdateReferences(boolean update) {
		fUpdateReferences= update;
	}
	
	public boolean getUpdateReferences(){
		return fUpdateReferences;
	}
		
	//-- getter/setter --------------------------------------------------
	
	/**
	 * @return Error message or <code>null</code> if getter can be renamed.
	 */
	public String canEnableGetterRenaming() throws CoreException{
		if (fField.getDeclaringType().isInterface())
			return getGetter() == null ? "": null; //$NON-NLS-1$
			
		IMethod getter= getGetter();
		if (getter == null) 
			return ""; //$NON-NLS-1$
		if (MethodChecks.isVirtual(getter) && null != MethodChecks.isDeclaredInInterface(getter, new NullProgressMonitor()))
			return DECLARED_SUPERTYPE;
		if (MethodChecks.isVirtual(getter) && null != MethodChecks.overridesAnotherMethod(getter, new NullProgressMonitor()))
			return DECLARED_SUPERTYPE;
		return null;	
	}
	
	/**
	 * @return Error message or <code>null</code> if setter can be renamed.
	 */
	public String canEnableSetterRenaming() throws CoreException{
		if (fField.getDeclaringType().isInterface())
			return getSetter() == null ? "": null; //$NON-NLS-1$
			
		IMethod setter= getSetter();
		if (setter == null) 
			return "";	 //$NON-NLS-1$
		if (MethodChecks.isVirtual(setter) && null != MethodChecks.isDeclaredInInterface(setter, new NullProgressMonitor()))
			return DECLARED_SUPERTYPE;
		if (MethodChecks.isVirtual(setter) && null != MethodChecks.overridesAnotherMethod(setter, new NullProgressMonitor()))
			return DECLARED_SUPERTYPE;
		return null;	
	}
	
	public boolean getRenameGetter() {
		return fRenameGetter;
	}

	public void setRenameGetter(boolean renameGetter) {
		fRenameGetter= renameGetter;
	}

	public boolean getRenameSetter() {
		return fRenameSetter;
	}

	public void setRenameSetter(boolean renameSetter) {
		fRenameSetter= renameSetter;
	}
	
	public IMethod getGetter() throws CoreException {
		return GetterSetterUtil.getGetter(fField);
	}
	
	public IMethod getSetter() throws CoreException {
		return GetterSetterUtil.getSetter(fField);
	}

	public String getNewGetterName() throws CoreException {
		IMethod primaryGetterCandidate= JavaModelUtil.findMethod(GetterSetterUtil.getGetterName(fField, new String[0]), new String[0], false, fField.getDeclaringType());
		if (! JavaModelUtil.isBoolean(fField) || (primaryGetterCandidate != null && primaryGetterCandidate.exists()))
			return NamingConventions.suggestGetterName(fField.getJavaProject(), fNewElementName, fField.getFlags(), JavaModelUtil.isBoolean(fField), null);
		//bug 30906 describes why we need to look for other alternatives here	
		return NamingConventions.suggestGetterName(fField.getJavaProject(), fNewElementName, fField.getFlags(), false, null);
	}

	public String getNewSetterName() throws CoreException {
		return NamingConventions.suggestSetterName(fField.getJavaProject(), fNewElementName, fField.getFlags(), JavaModelUtil.isBoolean(fField), null);
	}

	// -------------- Preconditions -----------------------
	
	public RefactoringStatus checkActivation() throws CoreException{
		IField orig= (IField)WorkingCopyUtil.getOriginal(fField);
		if (orig == null || ! orig.exists()){
			String message= RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.deleted", //$NON-NLS-1$
								fField.getCompilationUnit().getElementName());
			return RefactoringStatus.createFatalErrorStatus(message);
		}	
		fField= orig;
		
		return Checks.checkIfCuBroken(fField);
	}
	
	public RefactoringStatus checkInput(IProgressMonitor pm) throws CoreException {
		try{
			pm.beginTask("", 13); //$NON-NLS-1$
			pm.setTaskName(RefactoringCoreMessages.getString("RenameFieldRefactoring.checking")); //$NON-NLS-1$
			RefactoringStatus result= new RefactoringStatus();
			result.merge(Checks.checkIfCuBroken(fField));
			if (result.hasFatalError())
				return result;
			result.merge(checkNewElementName(fNewElementName));
			pm.worked(1);
			result.merge(checkEnclosingHierarchy());
			pm.worked(1);
			result.merge(checkNestedHierarchy(fField.getDeclaringType()));
			pm.worked(1);
			
			fReferences= null;
			if (fUpdateReferences){
				pm.setTaskName(RefactoringCoreMessages.getString("RenameFieldRefactoring.searching"));	 //$NON-NLS-1$
				fReferences= getReferences(new SubProgressMonitor(pm, 3));
				pm.setTaskName(RefactoringCoreMessages.getString("RenameFieldRefactoring.checking")); //$NON-NLS-1$
			} else {
				pm.worked(3);
			}	
			
			if (fUpdateReferences)
				result.merge(analyzeAffectedCompilationUnits());
				
			if (getGetter() != null && fRenameGetter){
				result.merge(checkAccessor(new SubProgressMonitor(pm, 1), getGetter(), getNewGetterName()));
				result.merge(Checks.checkIfConstructorName(getGetter(), getNewGetterName(), fField.getDeclaringType().getElementName()));
			}	
				
			if (getSetter() != null && fRenameSetter){
				result.merge(checkAccessor(new SubProgressMonitor(pm, 1), getSetter(), getNewSetterName()));
				result.merge(Checks.checkIfConstructorName(getSetter(), getNewSetterName(), fField.getDeclaringType().getElementName()));
			}	
			
			if (fUpdateReferences)
				result.merge(analyzeRenameChanges(new SubProgressMonitor(pm, 1)));
			else
				pm.worked(1);
			
			if (result.hasFatalError())
				return result;
			fChangeManager= createTextChangeManager(new SubProgressMonitor(pm, 5));
			result.merge(validateModifiesFiles());
			return result;
		} finally{
			pm.done();
		}
	}
	
	//----------
	private RefactoringStatus analyzeRenameChanges(IProgressMonitor pm) throws CoreException {
		try {
			pm.beginTask("", 3); //$NON-NLS-1$
			
			TextChangeManager manager= createTextChangeManager(new SubProgressMonitor(pm, 1));
			SearchResultGroup[] oldOccurrences= getOldOccurrences(new SubProgressMonitor(pm, 1));
			SearchResultGroup[] newOccurrences= getNewOccurrences(new SubProgressMonitor(pm, 1), manager);
			RefactoringStatus result= RenameAnalyzeUtil.analyzeRenameChanges(manager, oldOccurrences, newOccurrences);
			return result;
		} finally{
			pm.done();
			if (fNewWorkingCopies != null){
				for (int i= 0; i < fNewWorkingCopies.length; i++) {
					fNewWorkingCopies[i].destroy();
				}
			}	
		}
	}

	private SearchResultGroup[] getNewOccurrences(IProgressMonitor pm, TextChangeManager manager) throws CoreException {
		pm.beginTask("", 2); //$NON-NLS-1$
		ICompilationUnit[] compilationUnitsToModify= manager.getAllCompilationUnits();
		fNewWorkingCopies= RenameAnalyzeUtil.getNewWorkingCopies(compilationUnitsToModify, manager, new SubProgressMonitor(pm, 1));
		
		ICompilationUnit declaringCuWorkingCopy= RenameAnalyzeUtil.findWorkingCopyForCu(fNewWorkingCopies, fField.getCompilationUnit());
		if (declaringCuWorkingCopy == null)
			return new SearchResultGroup[0];
		
		IField field= getNewField(declaringCuWorkingCopy);
		if (field == null || ! field.exists())
			return new SearchResultGroup[0];
		
		ISearchPattern newPattern= SearchEngine.createSearchPattern(field, IJavaSearchConstants.ALL_OCCURRENCES);			
		return RefactoringSearchEngine.search(new SubProgressMonitor(pm, 1), createRefactoringScope(), newPattern, fNewWorkingCopies);
	}

	private SearchResultGroup[] getOldOccurrences(IProgressMonitor pm) throws CoreException {
		ISearchPattern oldPattern= SearchEngine.createSearchPattern(fField, IJavaSearchConstants.ALL_OCCURRENCES);				
		return RefactoringSearchEngine.search(pm, createRefactoringScope(), oldPattern);
	}
	
	private IField getNewField(ICompilationUnit newWorkingCopyOfDeclaringCu) throws CoreException{
		IType[] allNewTypes= newWorkingCopyOfDeclaringCu.getAllTypes();
		String fullyTypeName= fField.getDeclaringType().getFullyQualifiedName();
		for (int i= 0; i < allNewTypes.length; i++) {
			if (allNewTypes[i].getFullyQualifiedName().equals(fullyTypeName))
				return allNewTypes[i].getField(fNewElementName);
		}
		return null;
	}
	
	//----------
	private RefactoringStatus checkAccessor(IProgressMonitor pm, IMethod existingAccessor, String newAccessorName) throws CoreException{
		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkAccessorDeclarations(pm, existingAccessor));
		result.merge(checkNewAccessor(existingAccessor, newAccessorName));
		return result;
	}
	
	private RefactoringStatus checkNewAccessor(IMethod existingAccessor, String newAccessorName) throws CoreException{
		RefactoringStatus result= new RefactoringStatus();
		IMethod accessor= JavaModelUtil.findMethod(newAccessorName, existingAccessor.getParameterTypes(), false, fField.getDeclaringType());
		if (accessor == null || !accessor.exists())
			return null;
	
		String message= RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.already_exists", //$NON-NLS-1$
				new String[]{JavaElementUtil.createMethodSignature(accessor), JavaModelUtil.getFullyQualifiedName(fField.getDeclaringType())});
		result.addError(message, JavaStatusContext.create(accessor));
		return result;
	}
	
	private RefactoringStatus checkAccessorDeclarations(IProgressMonitor pm, IMethod existingAccessor) throws CoreException{
		RefactoringStatus result= new RefactoringStatus();
		ISearchPattern pattern= SearchEngine.createSearchPattern(existingAccessor, IJavaSearchConstants.DECLARATIONS);
		IJavaSearchScope scope= SearchEngine.createHierarchyScope(fField.getDeclaringType());
		SearchResultGroup[] groupDeclarations= RefactoringSearchEngine.search(pm, scope, pattern);
		Assert.isTrue(groupDeclarations.length > 0);
		if (groupDeclarations.length != 1){
			String message= RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.overridden", //$NON-NLS-1$
								JavaElementUtil.createMethodSignature(existingAccessor));
			result.addError(message);
		} else {
			SearchResultGroup group= groupDeclarations[0];
			Assert.isTrue(group.getSearchResults().length > 0);
			if (group.getSearchResults().length != 1){
				String message= RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.overridden_or_overrides", //$NON-NLS-1$
									JavaElementUtil.createMethodSignature(existingAccessor));
				result.addError(message);
			}	
		}	
		return result;
	}
	
	private static boolean isInstaceField(IField field) throws CoreException{
		if (field.getDeclaringType().isInterface())
			return false;
		else 
			return ! JdtFlags.isStatic(field);
	}
	
	private RefactoringStatus checkNestedHierarchy(IType type) throws CoreException {
		IType[] nestedTypes= type.getTypes();
		if (nestedTypes == null)
			return null;
		RefactoringStatus result= new RefactoringStatus();	
		for (int i= 0; i < nestedTypes.length; i++){
			IField otherField= nestedTypes[i].getField(getNewElementName());
			if (otherField.exists()){
				String msg= RefactoringCoreMessages.getFormattedString(
					"RenameFieldRefactoring.hiding", //$NON-NLS-1$
					new String[]{fField.getElementName(), getNewElementName(), JavaModelUtil.getFullyQualifiedName(nestedTypes[i])});
				result.addWarning(msg, JavaStatusContext.create(otherField));
			}									
			result.merge(checkNestedHierarchy(nestedTypes[i]));	
		}	
		return result;
	}
	
	private RefactoringStatus checkEnclosingHierarchy() throws CoreException {
		IType current= fField.getDeclaringType();
		if (Checks.isTopLevel(current))
			return null;
		RefactoringStatus result= new RefactoringStatus();
		while (current != null){
			IField otherField= current.getField(getNewElementName());
			if (otherField.exists()){
				String msg= RefactoringCoreMessages.getFormattedString("RenameFieldRefactoring.hiding2", //$NON-NLS-1$
				 															new String[]{getNewElementName(), JavaModelUtil.getFullyQualifiedName(current)});
				result.addWarning(msg, JavaStatusContext.create(otherField));
			}									
			current= current.getDeclaringType();
		}
		return result;
	}
	
	/*
	 * (non java-doc)
	 * Analyzes all compilation units in which type is referenced
	 */
	private RefactoringStatus analyzeAffectedCompilationUnits() throws CoreException{
		RefactoringStatus result= new RefactoringStatus();
		fReferences= Checks.excludeCompilationUnits(fReferences, result);
		if (result.hasFatalError())
			return result;
		
		result.merge(Checks.checkCompileErrorsInAffectedFiles(fReferences));	
		return result;
	}
	
	private IFile[] getAllFilesToModify() throws CoreException{
		return ResourceUtil.getFiles(fChangeManager.getAllCompilationUnits());
	}
	
	private RefactoringStatus validateModifiesFiles() throws CoreException{
		return Checks.validateModifiesFiles(getAllFilesToModify());
	}
	
	private ISearchPattern createSearchPattern(){
		return SearchEngine.createSearchPattern(fField, IJavaSearchConstants.REFERENCES);
	}
	
	private IJavaSearchScope createRefactoringScope() throws CoreException{
		return RefactoringScopeFactory.create(fField);
	}
	
	private SearchResultGroup[] getReferences(IProgressMonitor pm) throws CoreException{
		return RefactoringSearchEngine.search(new SubProgressMonitor(pm, 6), createRefactoringScope(), createSearchPattern());
	}
	
	// ---------- Changes -----------------

	/* non java-doc
	 * IRefactoring#createChange
	 */
	public IChange createChange(IProgressMonitor pm) throws CoreException {
		try{
			return new CompositeChange(RefactoringCoreMessages.getString("Change.javaChanges"), fChangeManager.getAllChanges()); //$NON-NLS-1$
		} finally{
			pm.done();
		}	
	}
	
	private TextChangeManager createTextChangeManager(IProgressMonitor pm) throws CoreException {
		try{
			pm.beginTask("", 3); //$NON-NLS-1$
				
			TextChangeManager manager= new TextChangeManager();
		
			addOccurrences(new SubProgressMonitor(pm, 1), manager);
		
			if (getGetter() != null && fRenameGetter)
				addGetterOccurrences(new SubProgressMonitor(pm, 1), manager);
			else
				pm.worked(1);
						
			if (getSetter() != null && fRenameSetter)
				addSetterOccurrences(new SubProgressMonitor(pm, 1), manager);
			else
				pm.worked(1);
					
			return manager;
		} finally{
			pm.done();
		}
	}
	
	private void addGetterOccurrences(IProgressMonitor pm, TextChangeManager manager) throws CoreException{
		addAccessorOccurrences(pm, manager, getGetter(), RefactoringCoreMessages.getString("RenameFieldRefactoring.Update_getter_occurrence"), getNewGetterName()); //$NON-NLS-1$
	}
	
	private void addSetterOccurrences(IProgressMonitor pm, TextChangeManager manager) throws CoreException{
		addAccessorOccurrences(pm, manager, getSetter(), RefactoringCoreMessages.getString("RenameFieldRefactoring.Update_setter_occurrence"), getNewSetterName()); //$NON-NLS-1$
	}

	private static void addAccessorOccurrences(IProgressMonitor pm, TextChangeManager manager, IMethod accessor, String editName, String newAccessorName) throws CoreException {
		Assert.isTrue(accessor.exists());
		
		IJavaSearchScope scope= RefactoringScopeFactory.create(accessor);
		ISearchPattern pattern= SearchEngine.createSearchPattern(accessor, IJavaSearchConstants.ALL_OCCURRENCES);
		SearchResultGroup[] groupedResults= RefactoringSearchEngine.search(pm, scope, pattern);
		
		for (int i= 0; i < groupedResults.length; i++) {
			ICompilationUnit cu= groupedResults[i].getCompilationUnit();
			if (cu == null)
				continue;
			SearchResult[] results= groupedResults[i].getSearchResults();
			for (int j= 0; j < results.length; j++){
				SearchResult searchResult= results[j];
				ICompilationUnit wc= WorkingCopyUtil.getWorkingCopyIfExists(cu);
				TextEdit edit= new UpdateMethodReferenceEdit(searchResult.getStart(), searchResult.getEnd() - searchResult.getStart(), newAccessorName, accessor.getElementName());
				manager.get(wc).addTextEdit(editName, edit);
			}
		}
	}
	
	private void addTextMatches(TextChangeManager manager, IProgressMonitor pm) throws CoreException {
		TextMatchFinder.findTextMatches(pm, createRefactoringScope(), this, manager);
	}	
	
	private void addOccurrences(IProgressMonitor pm, TextChangeManager manager) throws CoreException {
		pm.beginTask("", 5);//$NON-NLS-1$

		addDeclarationUpdate(manager);
		pm.worked(1);	
		
		if (fUpdateReferences)
			addReferenceUpdates(manager, new SubProgressMonitor(pm, 3));
		
		pm.subTask(RefactoringCoreMessages.getString("RenameFieldRefactoring.searching_for_text_matches")); //$NON-NLS-1$
		addTextMatches(manager, new SubProgressMonitor(pm, 1));
	}
	
	private void addDeclarationUpdate(TextChangeManager manager) throws CoreException{ 
		TextEdit textEdit= SimpleTextEdit.createReplace(fField.getNameRange().getOffset(), fField.getElementName().length(), fNewElementName);
		ICompilationUnit cu= WorkingCopyUtil.getWorkingCopyIfExists(fField.getCompilationUnit());
		manager.get(cu).addTextEdit(RefactoringCoreMessages.getString("RenameFieldRefactoring.Update_field_declaration"), textEdit); //$NON-NLS-1$
	}
	
	private void addReferenceUpdates(TextChangeManager manager, IProgressMonitor pm) throws CoreException{
		pm.beginTask("", fReferences.length); //$NON-NLS-1$
		String editName= RefactoringCoreMessages.getString("RenameFieldRefactoring.Update_field_reference"); //$NON-NLS-1$
		for (int i= 0; i < fReferences.length; i++){
			ICompilationUnit cu= fReferences[i].getCompilationUnit();
			if (cu == null)
				continue;
			SearchResult[] results= fReferences[i].getSearchResults();
			for (int j= 0; j < results.length; j++){
				ICompilationUnit wc= WorkingCopyUtil.getWorkingCopyIfExists(cu);
				manager.get(wc).addTextEdit(editName, createTextChange(results[j]));
			}
			pm.worked(1);			
		}
	}
	
	private TextEdit createTextChange(SearchResult searchResult) {
		int offset= searchResult.getStart();
		int length= searchResult.getEnd() - searchResult.getStart();
		return new UpdateFieldReference(offset, length, fNewElementName, fField.getElementName());
	}

	//-----------
	private final static class UpdateFieldReference extends SimpleTextEdit {

		private String fOldName;
		
		UpdateFieldReference(int offset, int length, String newName, String oldName){
			super(offset, length, newName);
			Assert.isNotNull(oldName);
			fOldName= oldName;
		}
		
		private UpdateFieldReference(TextRange range, String newName, String oldName) {
			super(range, newName);
			Assert.isNotNull(oldName);
			fOldName= oldName;
		}
	
		/* non Java-doc
		 * @see TextEdit#copy0
		 */
		protected TextEdit copy0(TextEditCopier copier) {
			return new UpdateFieldReference(getTextRange().copy(), getText(), fOldName);
		}
		
		/* non java-doc
		 * @see TextEdit#connect(TextBuffer)
		 */
		public void connect(TextBuffer buffer) throws CoreException {
			TextRange oldRange= getTextRange();
			int offset= oldRange.getOffset() + oldRange.getLength() - fOldName.length();
			setTextRange(new TextRange(offset, fOldName.length()));
		}
	}
}
