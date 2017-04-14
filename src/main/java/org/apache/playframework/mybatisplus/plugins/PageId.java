package org.apache.playframework.mybatisplus.plugins;


import com.baomidou.mybatisplus.plugins.Page;

public class PageId<T> extends Page<T> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1216657660502085024L;

	private Long indexId; //分页的id值

	public Long getIndexId() {
		return indexId;
	}

	public void setIndexId(Long indexId) {
		this.indexId = indexId;
	}
	
	public PageId(int current, int size, Long indexId) {
        super(current, size);
        this.indexId = indexId;
    }
	
	
}
