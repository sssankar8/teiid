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

package com.metamatrix.query.function;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import com.metamatrix.api.exception.query.FunctionExecutionException;
import com.metamatrix.common.types.DataTypeManager;
import com.metamatrix.common.types.TransformationException;
import com.metamatrix.common.util.PropertiesUtils;
import com.metamatrix.core.MetaMatrixRuntimeException;
import com.metamatrix.core.util.Assertion;
import com.metamatrix.core.util.HashCodeUtil;
import com.metamatrix.query.QueryPlugin;
import com.metamatrix.query.function.metadata.FunctionMethod;
import com.metamatrix.query.util.CommandContext;
import com.metamatrix.query.util.ErrorMessageKeys;

/**
 * The FunctionDescriptor describes a particular function instance enough
 * that the function library can retrieve a function instance based on the 
 * descriptor.
 */
public class FunctionDescriptor implements Serializable, Cloneable {
	
	private static final boolean ALLOW_NAN_INFINITY = PropertiesUtils.getBooleanProperty(System.getProperties(), "org.teiid.allowNanInfinity", false); //$NON-NLS-1$
	
	private String name;
    private int pushdown;
	private Class[] types;
	private Class returnType;	
	private int hash;
    private boolean requiresContext;
    private boolean nullDependent;
    private int deterministic;
    
    // This is transient as it would be useless to invoke this method in 
    // a different VM.  This function descriptor can be used to look up 
    // the real VM descriptor for execution.
    private transient Method invocationMethod;
	
    FunctionDescriptor() {
    }
    
    /** 
     * Construct a function descriptor with all the info
     * @param name Name of function
     * @param types Types of the arguments
     * @param returnType Type of the return 
     * @param invocationMethod Reflection method used to invoke the function
     * @param requiresContext during execution requires command context to be pushed into method as first argument
     */
	FunctionDescriptor(String name, int pushdown, Class[] types, Class returnType, Method invocationMethod, boolean requiresContext, boolean nullDependent, int deterministic) {
		Assertion.isNotNull(name);
		Assertion.isNotNull(types);
		Assertion.isNotNull(returnType);
		
		this.name = name;
        this.pushdown = pushdown;
		this.types = types;
		this.returnType = returnType;
        this.invocationMethod = invocationMethod;
        this.requiresContext = requiresContext;
        this.nullDependent = nullDependent;
        this.deterministic = deterministic;
		
		// Compute hash code
		hash = HashCodeUtil.hashCode(0, name);
		for(int i=0; i<types.length; i++) {
			hash = HashCodeUtil.hashCode(hash, types[i]);
		}
	}

	public String getName() {
		return this.name;				
	}
    
    public int getPushdown() {
        return this.pushdown;
    }
    
    void setPushdown(int pushdown) {
        this.pushdown = pushdown;
    }
	
	public Class[] getTypes() {
		return this.types;
	}
	
	public Class getReturnType() {
		return this.returnType;
	}		
	
    Method getInvocationMethod() {
        return this.invocationMethod;
    }
       
    public boolean requiresContext() {
        return this.requiresContext;
    }
    
	public int hashCode() { 
		return this.hash;
	}
	
	public boolean equals(Object obj) {
		if(obj == this) {
			return true;
		}
		
		if(obj == null || !(obj instanceof FunctionDescriptor)) {
			return false;			
		}	
		FunctionDescriptor other = (FunctionDescriptor) obj;
		
		// Compare names
		if(! this.getName().equals(other.getName())) {
			return false;
		}
        
        // Compare arg types
		Class[] thisTypes = this.getTypes();
		Class[] otherTypes = other.getTypes();
		if(thisTypes.length != otherTypes.length) {
			return false;
		}
		for(int i=0; i<thisTypes.length; i++) { 
			if(! thisTypes[i].equals(otherTypes[i])) {
				return false;
			}
		}
        
        if (this.nullDependent != other.isNullDependent()) {
            return false;
        }
        
        if (this.deterministic != other.deterministic) {
            return false;
        }
		 
		// Must be a match
		return true;
	}
	
	public String toString() {
		StringBuffer str = new StringBuffer(this.name);
		str.append("("); //$NON-NLS-1$
		for(int i=0; i<types.length; i++) {
			if(types[i] != null) { 
				str.append(types[i].getName());
			} else {
				str.append("null"); //$NON-NLS-1$
			}		
			if(i<(types.length-1)) {
				str.append(", "); //$NON-NLS-1$
			}
		}
		str.append(") : "); //$NON-NLS-1$
        if(returnType == null) { 
            str.append("null"); //$NON-NLS-1$
        } else {    
    		str.append(returnType.getName());
        }    
		return str.toString();
	}

    public boolean isNullDependent() {
        return nullDependent;
    }
    
    public int getDeterministic() {
        return deterministic;
    }

    void setDeterministic(int deterministic) {
        this.deterministic = deterministic;
    }
    
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new MetaMatrixRuntimeException(e);
        }
    }

    void setReturnType(Class returnType) {
        this.returnType = returnType;
    }
    
    
	/**
	 * Invoke the function described in the function descriptor, using the
	 * values provided.  Return the result of the function.
	 * @param fd Function descriptor describing the name and types of the arguments
	 * @param values Values that should match 1-to-1 with the types described in the
	 * function descriptor
	 * @return Result of invoking the function
	 */
	public Object invokeFunction(Object[] values) throws FunctionExecutionException {

        if (!isNullDependent()) {
        	for (int i = 0; i < values.length; i++) {
				if (values[i] == null) {
					return null;
				}
			}
        }

        // If descriptor is missing invokable method, find this VM's descriptor
        // give name and types from fd
        Method method = getInvocationMethod();
        if(method == null) {
        	throw new FunctionExecutionException(ErrorMessageKeys.FUNCTION_0002, QueryPlugin.Util.getString(ErrorMessageKeys.FUNCTION_0002, getName()));
        }
        
        if (getDeterministic() >= FunctionMethod.SESSION_DETERMINISTIC && values.length > 0 && values[0] instanceof CommandContext) {
        	CommandContext cc = (CommandContext)values[0];
        	cc.setSessionFunctionEvaluated(true);
        }
        
        // Invoke the method and return the result
        try {
        	if (method.isVarArgs()) {
        		int i = method.getParameterTypes().length;
        		Object[] newValues = Arrays.copyOf(values, i);
        		newValues[i - 1] = Arrays.copyOfRange(values, i - 1, values.length);
        		values = newValues;
        	}
            Object result = method.invoke(null, values);
            if (!ALLOW_NAN_INFINITY) {
        		if (result instanceof Double) {
	            	Double floatVal = (Double)result;
	            	if (Double.isInfinite(floatVal) || Double.isNaN(floatVal)) {
	            		throw new FunctionExecutionException(new ArithmeticException("Infinite or invalid result"), ErrorMessageKeys.FUNCTION_0003, QueryPlugin.Util.getString(ErrorMessageKeys.FUNCTION_0003, getName())); //$NON-NLS-1$
	            	}
	            } else if (result instanceof Float) {
	            	Float floatVal = (Float)result;
	            	if (Float.isInfinite(floatVal) || Float.isNaN(floatVal)) {
	            		throw new FunctionExecutionException(new ArithmeticException("Infinite or invalid result"), ErrorMessageKeys.FUNCTION_0003, QueryPlugin.Util.getString(ErrorMessageKeys.FUNCTION_0003, getName())); //$NON-NLS-1$
	            	}
	            }
        	}
            result = DataTypeManager.convertToRuntimeType(result);
            result = DataTypeManager.transformValue(result, getReturnType());
            return result;
        } catch(InvocationTargetException e) {
            throw new FunctionExecutionException(e.getTargetException(), ErrorMessageKeys.FUNCTION_0003, QueryPlugin.Util.getString(ErrorMessageKeys.FUNCTION_0003, getName()));
        } catch(IllegalAccessException e) {
            throw new FunctionExecutionException(e, ErrorMessageKeys.FUNCTION_0004, QueryPlugin.Util.getString(ErrorMessageKeys.FUNCTION_0004, method.toString()));
        } catch (TransformationException e) {
        	throw new FunctionExecutionException(e, e.getMessage());
		}
	}    
}
