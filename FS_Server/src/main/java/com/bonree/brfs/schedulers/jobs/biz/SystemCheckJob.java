package com.bonree.brfs.schedulers.jobs.biz;

import java.util.List;

import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bonree.brfs.common.task.TaskState;
import com.bonree.brfs.common.utils.BrStringUtils;
import com.bonree.brfs.common.utils.FileUtils;
import com.bonree.brfs.common.utils.JsonUtils;
import com.bonree.brfs.schedulers.jobs.JobDataMapConstract;
import com.bonree.brfs.schedulers.task.model.AtomTaskModel;
import com.bonree.brfs.schedulers.task.model.AtomTaskResultModel;
import com.bonree.brfs.schedulers.task.model.BatchAtomModel;
import com.bonree.brfs.schedulers.task.model.TaskResultModel;
import com.bonree.brfs.schedulers.task.operation.impl.QuartzOperationStateWithZKTask;
/******************************************************************************
 * 版权信息：北京博睿宏远数据科技股份有限公司
 * Copyright: Copyright (c) 2007北京博睿宏远数据科技股份有限公司,Inc.All Rights Reserved.
 * @param <AtomTaskModel>
 * 
 * @date 2018年5月3日 下午4:29:44
 * @Author: <a href=mailto:zhucg@bonree.com>朱成岗</a>
 * @Description:系统删除任务 
 *****************************************************************************
 */
public class SystemCheckJob extends QuartzOperationStateWithZKTask {
	private static final Logger LOG = LoggerFactory.getLogger("SystemDeleteJob");
	@Override
	public void caughtException(JobExecutionContext context) {
		LOG.info("Error ......   ");
		
	}

	@Override
	public void interrupt() throws UnableToInterruptJobException {
		LOG.info("interrupt ......   " );
		
	}

	@Override
	public void operation(JobExecutionContext context) throws Exception {
		LOG.info("check data //////////////////////////////////");
		JobDataMap data = context.getJobDetail().getJobDataMap();
		String currentIndex = data.getString(JobDataMapConstract.CURRENT_INDEX);
		String dataPath = data.getString(JobDataMapConstract.DATA_PATH);
		String content = data.getString(currentIndex);
		BatchAtomModel batch = JsonUtils.toObject(content, BatchAtomModel.class);
		if(batch == null){
			LOG.warn("batch data is empty !!!");
			return;
		}
		
		List<AtomTaskModel> atoms = batch.getAtoms();
		if(atoms == null || atoms.isEmpty()){
			LOG.warn("atom task is empty !!!");
			return;
		}
		String snName = null;
		String dirName = null;
		TaskResultModel result = new TaskResultModel();
		AtomTaskResultModel atomR = null;
		TaskResultModel batchResult = null;
		boolean isException = false;
		for(AtomTaskModel atom : atoms){
			snName = atom.getStorageName();
			dirName = atom.getDirName();
			if(BrStringUtils.isEmpty(snName)){
				LOG.warn("sn is empty !!!");
				continue;
			}
			if(BrStringUtils.isEmpty(dirName)){
				LOG.warn("dir is empty !!!");
				continue;
			}
			//调用俞朋的接口
			atomR = new AtomTaskResultModel();
			atomR.setSn(snName);
			atomR.setDir(dirName);
			if(!FileUtils.isExist(dirName)){
				LOG.warn("{} is not exists !!");
				continue;
			}
			batchResult = checkFiles(snName, dirName);
			if(batchResult == null){
				continue;
			}
			if(!batchResult.isSuccess()){
				result.setSuccess(batchResult.isSuccess());
			}
			result.addAll(batchResult.getAtoms());
		}
		//更新任务状态
		updateMapTaskMessage(context, result);
	}
	private TaskResultModel checkFiles(String snName, String dirName){
		return null;
	}

}
