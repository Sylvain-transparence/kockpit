import React, { useState, useEffect } from 'react';
import {
    BarChart, Bar, PieChart, Pie, LineChart, Line,
    XAxis, YAxis, CartesianGrid, Tooltip, Legend,
    ResponsiveContainer, Cell, Area, AreaChart
} from 'recharts';
import {
    Activity, Server, TrendingUp, AlertCircle,
    CheckCircle, Clock, Database, RefreshCw
} from 'lucide-react';
import {getAppDistributionData, getOverTimeByAppId, getStatusDistributionByAppId} from "../services/api.js";

function fillColor(index) {
    if (index === 0) {
        return "#93c5fd"
    } else if (index === 1) {
        return "#86efac"
    } else {
        const h = Math.floor(Math.random() * 360);
        const s = Math.floor(Math.random() * 30) + 70; // 70-100% saturation
        const l = Math.floor(Math.random() * 20) + 40; // 40-60% luminosité
        return `hsl(${h}, ${s}%, ${l}%)`;
    }
}

function strokeColor(index) {
    if (index === 0) {
        return "#3b82f6"
    } else if (index === 1) {
        return "#10b981"
    } else {
        const h = Math.floor(Math.random() * 360);
        const s = Math.floor(Math.random() * 30) + 50; // 50-80% saturation
        const l = Math.floor(Math.random() * 20) + 10; // 10-30% luminosité
        return `hsl(${h}, ${s}%, ${l}%)`;
    }
}

const MetricCard = ({ icon: Icon, title, value, subtitle, trend, color = "blue" }) => {
    const colorClasses = {
        blue: 'bg-blue-50 text-blue-600',
        green: 'bg-green-50 text-green-600',
        yellow: 'bg-yellow-50 text-yellow-600',
        red: 'bg-red-50 text-red-600'
    };

    return (
        <div className="bg-white rounded-lg shadow p-6 border border-gray-100">
            <div className="flex items-center justify-between">
                <div className="flex items-center">
                    <div className={`p-3 rounded-lg ${colorClasses[color]}`}>
                        <Icon className="w-6 h-6" />
                    </div>
                    <div className="ml-4">
                        <p className="text-sm text-gray-500">{title}</p>
                        <p className="text-2xl font-semibold text-gray-900">{value}</p>
                        {subtitle && <p className="text-xs text-gray-400 mt-1">{subtitle}</p>}
                    </div>
                </div>
                {trend && (
                    <div className={`flex items-center ${trend > 0 ? 'text-green-500' : 'text-red-500'}`}>
                        <TrendingUp className={`w-4 h-4 ${trend < 0 ? 'rotate-180' : ''}`} />
                        <span className="text-sm ml-1">{Math.abs(trend)}%</span>
                    </div>
                )}
            </div>
        </div>
    );
};

function timeSeriesApplications(timeSeriesData) {
    let apps = [];
    timeSeriesData.forEach(data => {
        Object.keys(data).forEach(key => {
            if (key !== 'date' && apps.indexOf(key) === -1) {
                apps.push(key);
            }
        })
    })
    return apps;
}

function generateStatusDistribution(data) {
    // 1. Calculer les totaux pour chaque statut
    const totals = {
        '2xx': 0,
        '3xx': 0,
        '4xx': 0,
        '5xx': 0
    };

    // Sommer tous les statuts
    data.forEach(app => {
        totals['2xx'] += app['2xx'] || 0;
        totals['3xx'] += app['3xx'] || 0;
        totals['4xx'] += app['4xx'] || 0;
        totals['5xx'] += app['5xx'] || 0;
    });

    // 2. Calculer le total global
    const grandTotal = Object.values(totals).reduce((sum, val) => sum + val, 0);

    // 3. Configuration des statuts
    const statusConfig = [
        { key: '2xx', name: '2xx Success', color: '#10b981' },
        { key: '3xx', name: '3xx Redirect', color: '#3b82f6' },
        { key: '4xx', name: '4xx Client Error', color: '#f59e0b' },
        { key: '5xx', name: '5xx Server Error', color: '#ef4444' }
    ];

    // 4. Générer le tableau final
    const result = statusConfig.map(status => ({
        name: status.name,
        value: totals[status.key],
        percentage: grandTotal > 0
            ? parseFloat(((totals[status.key] / grandTotal) * 100).toFixed(1))
            : 0,
        color: status.color
    }));

    return result;
}

const AppIdDashboard = ({domain, env}) => {
    const [loading, setLoading] = useState(false);
    const [selectedTimeRange, setSelectedTimeRange] = useState('24h');
    //const [selectedApp, setSelectedApp] = useState('all');

    // Simulated data - In production, this would come from OpenSearch queries
    const [appDistributionData, setAppDistributionData] = useState([]);
    const [totalRequests, setTotalRequests] = useState(0);
    const [avgResponseTime, setAvgResponseTime] = useState(0);
    const [statusByAppData, setStatusByAppData]  = useState([]);
    const [statusDistribution, setStatusDistribution] = useState([]);
    const [timeSeriesData, setTimeSeriesData] = useState([]);

    let successRate;
    let errorRate;

    useEffect(() => {
        loadData();
    }, [selectedTimeRange, domain, env]);

    successRate = statusDistribution.find(s => s.name.includes('2xx'))?.percentage || 0;
    errorRate = (statusDistribution.find(s => s.name.includes('4xx'))?.percentage || 0) +
        (statusDistribution.find(s => s.name.includes('5xx'))?.percentage || 0);

    const handleRefresh = () => {
        loadData();
    };

    const loadData = () => {
        setLoading(true);

        Promise.all([
            getAppDistributionData(domain, env, selectedTimeRange).then(rawData => {
                const data = Array.isArray(rawData) ? rawData : [];
                setAppDistributionData(data);
                const totalDocs = data.reduce((sum, app) => sum + app.count, 0);
                setTotalRequests(totalDocs);

                const totalDuration = data.reduce((sum, bucket) => {
                    const avgDuration = bucket.avgDuration;
                    return sum + (avgDuration * bucket.count);
                }, 0);

                const overallAvg = totalDuration / totalDocs;

                setAvgResponseTime(overallAvg);
            }),

            getStatusDistributionByAppId(domain, env, selectedTimeRange).then(data => {
                const arrayData = Array.isArray(data) ? data : [];
                setStatusByAppData(arrayData);
                setStatusDistribution(generateStatusDistribution(arrayData));
            }),

            getOverTimeByAppId(domain, env, '1d').then(data => {
                console.log(`timeSeries ${JSON.stringify(data)}`);
                /*
                const transformed = data.map(d => {
                    if (d.hour) {
                        d.hour = new Date(d.hour).getHours();
                    }
                    return d;
                });//.sort((a, b) => a.hour - b.hour);
                 */
                setTimeSeriesData(Array.isArray(data) ? data : []);
            })
        ]).finally(() => {
            setLoading(false);
        });
    };

    return (
        <div className="min-h-screen bg-gray-50 p-6 relative">
            {loading && (
                <>
                    <div className="absolute inset-0 bg-gray-900 bg-opacity-10 backdrop-blur-sm z-40"></div>
                    <div className="absolute top-20 left-1/2 transform -translate-x-1/2 z-50">
                        <div className="bg-white p-6 rounded-lg shadow-lg flex items-center gap-3">
                            <RefreshCw className="w-6 h-6 animate-spin text-blue-600" />
                            <span className="text-gray-700 font-medium">Loading dashboard data...</span>
                        </div>
                    </div>
                </>
            )}
            <div className={`max-w-7xl mx-auto ${loading ? 'blur-sm' : ''}`}>
                {/* Header */}
                <div className="mb-8">
                    <div className="flex justify-between items-center">
                        <div>
                            <h1 className="text-3xl font-bold text-gray-900">Overview</h1>
                        </div>
                        <div className="flex gap-3">
                            <select
                                className="px-4 py-2 border border-gray-300 rounded-lg bg-white"
                                value={selectedTimeRange}
                                onChange={(e) => setSelectedTimeRange(e.target.value)}
                            >
                                <option value="1h">Last Hour</option>
                                <option value="24h">Last 24 Hours</option>
                                <option value="7d">Last 7 Days</option>
                                <option value="30d">Last 30 Days</option>
                            </select>
                            <button
                                onClick={handleRefresh}
                                className={`px-4 py-2 bg-blue-600 text-white rounded-lg flex items-center gap-2 hover:bg-blue-700 transition ${loading ? 'opacity-75' : ''}`}
                                disabled={loading}
                            >
                                <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
                                Refresh
                            </button>
                        </div>
                    </div>
                </div>

                {/* Metrics Grid */}
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
                    <MetricCard
                        icon={Server}
                        title="Total Requests"
                        value={totalRequests.toLocaleString()}
                        subtitle={`${appDistributionData.length} applications`}
                        // trend={12} // fixme
                        color="blue"
                    />
                    <MetricCard
                        icon={Clock}
                        title="Avg Response Time"
                        value={`${avgResponseTime.toFixed(2)} ms`}
                        subtitle="Across all apps"
                        //trend={-8} // fixme
                        color="yellow"
                    />
                    <MetricCard
                        icon={CheckCircle}
                        title="Success Rate"
                        value={`${successRate}%`}
                        subtitle="2xx responses"
                        //trend={3} // fixme
                        color="green"
                    />
                    <MetricCard
                        icon={AlertCircle}
                        title="Error Rate"
                        value={`${errorRate.toFixed(1)}%`}
                        subtitle="4xx + 5xx responses"
                        // trend={-15} fixme
                        color="red"
                    />
                </div>

                {/* Application Details Table */}
                <div className="bg-white rounded-lg shadow p-6 mt-6">
                    <h3 className="text-lg font-semibold text-gray-900 mb-4">Application Details</h3>
                    <div className="overflow-x-auto">
                        <table className="min-w-full divide-y divide-gray-200">
                            <thead className="bg-gray-50">
                            <tr>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Application</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Total Requests</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Avg Duration</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Success Rate</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Error Rate</th>
                                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                            </tr>
                            </thead>
                            <tbody className="bg-white divide-y divide-gray-200">
                            {appDistributionData.map((app, index) => {
                                const appStatus = statusByAppData[index];
                                if (!appStatus) return null;
                                let total = 0;
                                ['2xx', '3xx', '4xx', '5xx'].forEach(status => {
                                    if (appStatus[status]) {
                                        total += appStatus[status];
                                    }
                                })
                                let successRate = 0;
                                if (appStatus['2xx']) {
                                    successRate = ((appStatus['2xx'] / total) * 100).toFixed(1);
                                }
                                let errorRate = 0;
                                if (appStatus['4xx'] || appStatus['5xx']) {
                                    if (appStatus['4xx']) {
                                        errorRate += appStatus['4xx'];
                                    }
                                    if (appStatus['5xx']) {
                                        errorRate += appStatus['5xx'];
                                    }
                                    errorRate = ((errorRate / total) * 100).toFixed(1);
                                }

                                return (
                                    <tr key={app.name} className="hover:bg-gray-50">
                                        <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{app.name}</td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{app.count.toLocaleString()}</td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{app.avgDuration != null ? `${app.avgDuration.toFixed(2)} ms` : 'N/A'}</td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                            <span className="text-green-600 font-medium">{successRate}%</span>
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        <span className={`font-medium ${parseFloat(errorRate) > 5 ? 'text-red-600' : 'text-yellow-600'}`}>
                          {errorRate}%
                        </span>
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                            parseFloat(errorRate) < 2 ? 'bg-green-100 text-green-800' :
                                parseFloat(errorRate) < 5 ? 'bg-yellow-100 text-yellow-800' :
                                    'bg-red-100 text-red-800'
                        }`}>
                          {parseFloat(errorRate) < 2 ? 'Healthy' : parseFloat(errorRate) < 5 ? 'Warning' : 'Critical'}
                        </span>
                                        </td>
                                    </tr>
                                );
                            })}
                            </tbody>
                        </table>
                    </div>
                </div>

                {/* Charts Grid */}
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6 mt-6">

                    {/* Application Distribution */}
                    <div className="bg-white rounded-lg shadow p-6">
                        <h3 className="text-lg font-semibold text-gray-900 mb-4">Request Distribution by Application</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <BarChart data={appDistributionData}>
                                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                                <XAxis dataKey="name" angle={-45} textAnchor="end" height={80} />
                                <YAxis />
                                <Tooltip
                                    contentStyle={{ backgroundColor: '#fff', border: '1px solid #e5e7eb', borderRadius: '8px' }}
                                    formatter={(value) => value.toLocaleString()}
                                />
                                <Bar dataKey="count" fill="#3b82f6" radius={[8, 8, 0, 0]}>
                                    {appDistributionData.map((entry, index) => (
                                        <Cell key={`cell-${index}`} fill={index === 0 ? '#3b82f6' : '#93c5fd'} />
                                    ))}
                                </Bar>
                            </BarChart>
                        </ResponsiveContainer>
                    </div>

                    {/* HTTP Status Distribution */}
                    <div className="bg-white rounded-lg shadow p-6">
                        <h3 className="text-lg font-semibold text-gray-900 mb-4">HTTP Status Distribution</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <PieChart>
                                <Pie
                                    data={statusDistribution}
                                    cx="50%"
                                    cy="50%"
                                    labelLine={false}
                                    label={({ name, percentage }) => `${name.split(' ')[0]} (${percentage}%)`}
                                    outerRadius={100}
                                    fill="#8884d8"
                                    dataKey="value"
                                >
                                    {statusDistribution.map((entry, index) => (
                                        <Cell key={`cell-${index}`} fill={entry.color} />
                                    ))}
                                </Pie>
                                <Tooltip formatter={(value) => value.toLocaleString()} />
                            </PieChart>
                        </ResponsiveContainer>
                    </div>
                </div>

                {/* Time Series Chart */}
                <div className="bg-white rounded-lg shadow p-6 mb-6">
                    <h3 className="text-lg font-semibold text-gray-900 mb-4">Request Volume Over Time</h3>
                    <ResponsiveContainer width="100%" height={350}>
                        <AreaChart data={timeSeriesData}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                            <XAxis dataKey="date" tickFormatter={(value) => new Date(value).toLocaleTimeString()} />
                            <YAxis />
                            <Tooltip
                                contentStyle={{ backgroundColor: '#fff', border: '1px solid #e5e7eb', borderRadius: '8px' }}
                            />
                            <Legend />
                            {
                                timeSeriesApplications(timeSeriesData).map((key, index) => {
                                    return (<Area type="monotone" dataKey={key} stackId={index} stroke={strokeColor(index)} fill={fillColor(index)} />)
                                })
                            }

                        </AreaChart>
                    </ResponsiveContainer>
                </div>

                {/* HTTP Status by Application */}
                <div className="bg-white rounded-lg shadow p-6">
                    <h3 className="text-lg font-semibold text-gray-900 mb-4">HTTP Status Codes by Application</h3>
                    <ResponsiveContainer width="100%" height={350}>
                        <BarChart data={statusByAppData}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                            <XAxis dataKey="name" />
                            <YAxis />
                            <Tooltip
                                contentStyle={{ backgroundColor: '#fff', border: '1px solid #e5e7eb', borderRadius: '8px' }}
                            />
                            <Legend />
                            <Bar dataKey="2xx" stackId="a" fill="#10b981" />
                            <Bar dataKey="3xx" stackId="a" fill="#3b82f6" />
                            <Bar dataKey="4xx" stackId="a" fill="#f59e0b" />
                            <Bar dataKey="5xx" stackId="a" fill="#ef4444" />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

            </div>
        </div>
    );
};

export default AppIdDashboard;
