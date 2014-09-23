/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.xasecure.audit.provider.hdfs;


import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Date;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.helpers.LogLog;

import com.xasecure.audit.provider.LogDestination;
import com.xasecure.audit.provider.MiscUtil;

public class HdfsLogDestination<T> implements LogDestination<T> {
	private String  mDirectory                = null;
	private String  mFile                     = null;
	private int     mFlushIntervalSeconds     = 1 * 60;
	private String  mEncoding                 = null;
	private boolean mIsAppend                 = true;
	private int     mRolloverIntervalSeconds  = 24 * 60 * 60;
	private int     mOpenRetryIntervalSeconds = 60;

	private OutputStreamWriter mWriter             = null; 
	private String             mHdfsFilename       = null;
	private long               mNextRolloverTime   = 0;
	private long               mNextFlushTime      = 0;
	private long               mLastOpenFailedTime = 0;
	private boolean            mIsStopInProgress   = false;

	public HdfsLogDestination() {
	}

	public String getDirectory() {
		return mDirectory;
	}

	public void setDirectory(String directory) {
		this.mDirectory = directory;
	}

	public String getFile() {
		return mFile;
	}

	public void setFile(String file) {
		this.mFile = file;
	}

	public int getFlushIntervalSeconds() {
		return mFlushIntervalSeconds;
	}

	public void setFlushIntervalSeconds(int flushIntervalSeconds) {
		mFlushIntervalSeconds = flushIntervalSeconds;
	}

	public String getEncoding() {
		return mEncoding;
	}

	public void setEncoding(String encoding) {
		mEncoding = encoding;
	}

	public int getRolloverIntervalSeconds() {
		return mRolloverIntervalSeconds;
	}

	public void setRolloverIntervalSeconds(int rolloverIntervalSeconds) {
		this.mRolloverIntervalSeconds = rolloverIntervalSeconds;
	}

	public int getOpenRetryIntervalSeconds() {
		return mOpenRetryIntervalSeconds;
	}

	public void setOpenRetryIntervalSeconds(int minIntervalOpenRetrySeconds) {
		this.mOpenRetryIntervalSeconds = minIntervalOpenRetrySeconds;
	}

	@Override
	public void start() {
		LogLog.debug("==> HdfsLogDestination.start()");

		openFile();

		LogLog.debug("<== HdfsLogDestination.start()");
	}

	@Override
	public void stop() {
		LogLog.debug("==> HdfsLogDestination.stop()");

		mIsStopInProgress = true;

		closeFile();

		mIsStopInProgress = false;

		LogLog.debug("<== HdfsLogDestination.stop()");
	}

	@Override
	public boolean isAvailable() {
		return mWriter != null;
	}

	@Override
	public boolean send(T log) {
		boolean ret = false;
		
		if(log != null) {
			String msg = log.toString();

			ret = sendStringified(msg);
		}

		return ret;
	}

	@Override
	public boolean sendStringified(String log) {
		boolean ret = false;

		checkFileStatus();

		OutputStreamWriter writer = mWriter;

		if(writer != null) {
			try {
				writer.write(log + MiscUtil.LINE_SEPARATOR);

				ret = true;
			} catch (IOException excp) {
				LogLog.warn("HdfsLogDestination.sendStringified(): write failed", excp);

				closeFile();
			}
		}

		return ret;
	}

	private void openFile() {
		LogLog.debug("==> HdfsLogDestination.openFile()");

		closeFile();

		mNextRolloverTime = MiscUtil.getNextRolloverTime(mNextRolloverTime, (mRolloverIntervalSeconds * 1000L));

		long startTime = MiscUtil.getRolloverStartTime(mNextRolloverTime, (mRolloverIntervalSeconds * 1000L));

		mHdfsFilename = MiscUtil.replaceTokens(mDirectory + org.apache.hadoop.fs.Path.SEPARATOR + mFile, startTime);

		FSDataOutputStream ostream     = null;
		FileSystem         fileSystem  = null;
		Path               pathLogfile = null;
		Configuration      conf        = null;

		try {
			LogLog.debug("HdfsLogDestination.openFile(): opening file " + mHdfsFilename);

			URI uri = URI.create(mHdfsFilename);

			// TODO: mechanism to XA-HDFS plugin to disable auditing of access checks to the current HDFS file

			conf        = new Configuration();
			pathLogfile = new Path(mHdfsFilename);
			fileSystem  = FileSystem.get(uri, conf);

			if(fileSystem.exists(pathLogfile)) {
				if(mIsAppend) {
					try {
						ostream = fileSystem.append(pathLogfile);
					} catch(IOException excp) {
						// append may not be supported by the filesystem. rename existing file and create a new one
						String fileSuffix    = MiscUtil.replaceTokens("-" + MiscUtil.TOKEN_TIME_START + "yyyyMMdd-HHmm.ss" + MiscUtil.TOKEN_TIME_END, startTime);
						String movedFilename = appendToFilename(mHdfsFilename, fileSuffix);
						Path   movedFilePath = new Path(movedFilename);

						fileSystem.rename(pathLogfile, movedFilePath);
					}
				}
			}

			if(ostream == null){
				ostream = fileSystem.create(pathLogfile);
			}
		} catch(IOException ex) {
			Path parentPath = pathLogfile.getParent();

			try {
				if(parentPath != null&& fileSystem != null && !fileSystem.exists(parentPath) && fileSystem.mkdirs(parentPath)) {
					ostream = fileSystem.create(pathLogfile);
				}
			} catch (IOException e) {
				LogLog.warn("HdfsLogDestination.openFile() failed", e);
			} catch (Throwable e) {
				LogLog.warn("HdfsLogDestination.openFile() failed", e);
			}
		} catch(Throwable ex) {
			LogLog.warn("HdfsLogDestination.openFile() failed", ex);
		} finally {
			// TODO: unset the property set above to exclude auditing of logfile opening
			//        System.setProperty(hdfsCurrentFilenameProperty, null);
		}

		mWriter = createWriter(ostream);

		if(mWriter != null) {
			LogLog.debug("HdfsLogDestination.openFile(): opened file " + mHdfsFilename);

			mNextFlushTime      = System.currentTimeMillis() + (mFlushIntervalSeconds * 1000L);
			mLastOpenFailedTime = 0;
		} else {
			LogLog.warn("HdfsLogDestination.openFile(): failed to open file for write " + mHdfsFilename);

			mHdfsFilename = null;
			mLastOpenFailedTime = System.currentTimeMillis();
		}

		LogLog.debug("<== HdfsLogDestination.openFile(" + mHdfsFilename + ")");
	}

	private void closeFile() {
		LogLog.debug("==> HdfsLogDestination.closeFile()");

		OutputStreamWriter writer = mWriter;

		mWriter = null;

		if(writer != null) {
			try {
				writer.flush();
				writer.close();
			} catch(IOException excp) {
				if(! mIsStopInProgress) { // during shutdown, the underlying FileSystem might already be closed; so don't print error details
					LogLog.warn("HdfsLogDestination: failed to close file " + mHdfsFilename, excp);
				}
			}
		}

		LogLog.debug("<== HdfsLogDestination.closeFile()");
	}

	private void rollover() {
		LogLog.debug("==> HdfsLogDestination.rollover()");

		closeFile();

		openFile();

		LogLog.debug("<== HdfsLogDestination.rollover()");
	}

	private void checkFileStatus() {
		long now = System.currentTimeMillis();

		if(mWriter == null) {
			if(now > (mLastOpenFailedTime + (mOpenRetryIntervalSeconds * 1000L))) {
				openFile();
			}
		} else  if(now > mNextRolloverTime) {
			rollover();
		} else if(now > mNextFlushTime) {
			try {
				mWriter.flush();

				mNextFlushTime = now + (mFlushIntervalSeconds * 1000L);
			} catch (IOException excp) {
				LogLog.warn("HdfsLogDestination: failed to flush", excp);
			}
		}
	}

	private OutputStreamWriter createWriter(OutputStream os ) {
	    OutputStreamWriter writer = null;

	    if(os != null) {
			if(mEncoding != null) {
				try {
					writer = new OutputStreamWriter(os, mEncoding);
				} catch(UnsupportedEncodingException excp) {
					LogLog.warn("HdfsLogDestination.createWriter(): failed to create output writer.", excp);
				}
			}
	
			if(writer == null) {
				writer = new OutputStreamWriter(os);
			}
	    }

	    return writer;
	}
	
	private String appendToFilename(String fileName, String strToAppend) {
		String ret = fileName;
		
		if(strToAppend != null) {
			if(ret == null) {
				ret = "";
			}
	
			int extnPos = ret.lastIndexOf(".");
			
			if(extnPos < 0) {
				ret += strToAppend;
			} else {
				String extn = ret.substring(extnPos);
				
				ret = ret.substring(0, extnPos) + strToAppend + extn;
			}
		}

		return ret;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		sb.append("HdfsLogDestination {");
		sb.append("Directory=").append(mDirectory).append("; ");
		sb.append("File=").append(mFile).append("; ");
		sb.append("RolloverIntervalSeconds=").append(mRolloverIntervalSeconds);
		sb.append("}");
		
		return sb.toString();
	}
}