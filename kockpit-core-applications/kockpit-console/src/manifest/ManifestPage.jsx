import React, {useEffect, useState} from "react";
import {getManifests, saveManifest} from "../services/manifestApi.js";
import {Clock, Download, Server, Upload, X} from "lucide-react";

const ManifestCard = ({ manifest, onClick }) => {
    const handleDownload = (e) => {
        e.stopPropagation();
        const jsonString = JSON.stringify(manifest, null, 2);
        const blob = new Blob([jsonString], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = manifest.name || `manifest.json`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
    };

    return (
        <div
            onClick={onClick}
            className="bg-white rounded-lg shadow-md hover:shadow-lg transition-all duration-200 p-6 cursor-pointer border border-slate-200 hover:border-blue-400"
        >
            <div className="flex items-start justify-between mb-4">
                <div className="flex items-center gap-3">
                    <div className="p-2 bg-blue-100 rounded-lg">
                        <Server className="h-6 w-6 text-blue-600" />
                    </div>
                    <div>
                        <h3 className="text-lg font-semibold text-slate-800">
                            {manifest.domain} / {manifest.env}
                        </h3>
                        <p className="text-sm text-slate-500">{manifest.name}</p>
                    </div>
                </div>
                <button
                    onClick={handleDownload}
                    className="p-2 hover:bg-blue-100 rounded-lg transition-colors"
                    title="Download manifest"
                >
                    <Download className="h-5 w-5 text-blue-600" />
                </button>
            </div>

            {manifest.services && manifest.services.length > 0 && (
                <div className="mt-4">
                    <p className="text-sm text-slate-600 mb-2">
                        Services: {manifest.services.length}
                    </p>
                    <div className="flex flex-wrap gap-2">
                        {manifest.services.slice(0, 5).map((service, idx) => (
                            <div
                                key={idx}
                                className="px-2 py-1 bg-slate-100 text-slate-700 text-xs rounded-md flex flex-col"
                            >
                                <span className="font-semibold">{service.name || service.type}</span>
                                {service.name && service.type && (
                                    <span className="text-slate-500 text-[10px] mt-0.5">{service.type}</span>
                                )}
                            </div>
                        ))}
                        {manifest.services.length > 5 && (
                            <span className="px-2 py-1 bg-slate-100 text-slate-500 text-xs rounded-md">
                                +{manifest.services.length - 5} more
                            </span>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
};

const ServiceDetails = ({ service }) => {
    return (
        <div className="bg-white rounded-lg border border-slate-200 p-4 hover:shadow-md transition-shadow">
            <div className="flex items-start justify-between mb-3">
                <div>
                    <h4 className="font-semibold text-slate-800">{service.name}</h4>
                    <p className="text-sm text-slate-500 mt-1">Type: {service.type}</p>
                    {service.id && (
                        <p className="text-sm text-slate-500">ID: {service.id}</p>
                    )}
                </div>
                {service.version && (
                    <span className="px-2 py-1 bg-blue-100 text-blue-700 text-xs rounded-md font-medium">
                        v{service.version}
                    </span>
                )}
            </div>

            {service.label && (
                <div className="mb-2">
                    <p className="text-sm text-slate-600">{service.label}</p>
                </div>
            )}

            {service.config && Object.keys(service.config).length > 0 && (
                <div className="mt-3 pt-3 border-t border-slate-100">
                    <p className="text-xs font-semibold text-slate-500 mb-2">Configuration</p>
                    <div className="bg-slate-50 rounded p-2">
                        <pre className="text-xs text-slate-700 overflow-x-auto">
                            {JSON.stringify(service.config, null, 2)}
                        </pre>
                    </div>
                </div>
            )}
        </div>
    );
};

const ManifestDetails = ({ manifest, onClose }) => {
    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-xl shadow-2xl max-w-4xl w-full max-h-[90vh] overflow-hidden flex flex-col">
                <div className="p-6 border-b border-slate-200 flex items-center justify-between bg-gradient-to-r from-blue-50 to-slate-50">
                    <div>
                        <h2 className="text-2xl font-bold text-slate-800">
                            {manifest.domain} / {manifest.env}
                        </h2>
                        <p className="text-sm text-slate-500 mt-1">{manifest.name}</p>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-slate-200 rounded-lg transition-colors"
                    >
                        <X className="h-6 w-6 text-slate-600" />
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto p-6">
                    {manifest.services && manifest.services.length > 0 ? (
                        <div className="space-y-4">
                            <h3 className="text-lg font-semibold text-slate-800 mb-4">
                                Services ({manifest.services.length})
                            </h3>
                            {manifest.services.map((service, idx) => (
                                <ServiceDetails key={idx} service={service} />
                            ))}
                        </div>
                    ) : (
                        <div className="text-center py-12 text-slate-500">
                            <Server className="h-12 w-12 mx-auto mb-3 text-slate-300" />
                            <p>No services configured in this manifest</p>
                        </div>
                    )}
                </div>

                <div className="p-6 border-t border-slate-200 bg-slate-50">
                    <button
                        onClick={onClose}
                        className="px-4 py-2 bg-slate-600 text-white rounded-lg hover:bg-slate-700 transition-colors"
                    >
                        Close
                    </button>
                </div>
            </div>
        </div>
    );
};

const UploadZone = ({ onUploadSuccess }) => {
    const [isDragging, setIsDragging] = useState(false);
    const [uploading, setUploading] = useState(false);
    const [uploadMessage, setUploadMessage] = useState(null);
    const fileInputRef = React.useRef(null);

    const handleDragOver = (e) => {
        e.preventDefault();
        setIsDragging(true);
    };

    const handleDragLeave = (e) => {
        e.preventDefault();
        setIsDragging(false);
    };

    const handleDrop = async (e) => {
        e.preventDefault();
        setIsDragging(false);

        const files = e.dataTransfer.files;
        if (files.length > 0) {
            await handleFile(files[0]);
        }
    };

    const handleFileInput = async (e) => {
        const files = e.target.files;
        if (files.length > 0) {
            await handleFile(files[0]);
        }
    };

    const handleFile = async (file) => {
        if (!file.name.endsWith('.json')) {
            setUploadMessage({ type: 'error', text: 'Please upload a JSON file' });
            setTimeout(() => setUploadMessage(null), 3000);
            return;
        }

        try {
            setUploading(true);
            setUploadMessage(null);

            const text = await file.text();
            const manifest = JSON.parse(text);

            await saveManifest(manifest);

            setUploadMessage({ type: 'success', text: `Manifest "${manifest.name || file.name}" uploaded successfully!` });
            setTimeout(() => setUploadMessage(null), 3000);

            if (onUploadSuccess) {
                onUploadSuccess();
            }
        } catch (err) {
            console.error('Upload error:', err);
            // the backend explains a 400 (invalid name/domain/env) or 403 (not your manifest/domain)
            const reason = err.response?.data?.message || err.message;
            setUploadMessage({ type: 'error', text: `Failed to upload manifest: ${reason}` });
            setTimeout(() => setUploadMessage(null), 5000);
        } finally {
            setUploading(false);
            if (fileInputRef.current) {
                fileInputRef.current.value = '';
            }
        }
    };

    return (
        <div className="mb-8">
            <div
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
                className={`border-2 border-dashed rounded-lg p-8 text-center cursor-pointer transition-all ${
                    isDragging
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-slate-300 bg-white hover:border-blue-400 hover:bg-blue-50'
                }`}
            >
                <input
                    ref={fileInputRef}
                    type="file"
                    accept=".json"
                    onChange={handleFileInput}
                    className="hidden"
                />

                <Upload className={`h-12 w-12 mx-auto mb-3 ${isDragging ? 'text-blue-600' : 'text-slate-400'}`} />

                {uploading ? (
                    <div className="text-blue-600">
                        <Clock className="h-8 w-8 animate-spin mx-auto mb-2" />
                        <p className="font-semibold">Uploading manifest...</p>
                    </div>
                ) : (
                    <>
                        <h3 className="text-lg font-semibold text-slate-700 mb-1">
                            Upload Manifest
                        </h3>
                        <p className="text-sm text-slate-500 mb-2">
                            Drag and drop a JSON file here, or click to browse
                        </p>
                        <p className="text-xs text-slate-400">
                            Only .json files are accepted
                        </p>
                    </>
                )}
            </div>

            {uploadMessage && (
                <div className={`mt-3 p-3 rounded-lg ${
                    uploadMessage.type === 'success'
                        ? 'bg-green-50 text-green-700 border border-green-200'
                        : 'bg-red-50 text-red-700 border border-red-200'
                }`}>
                    {uploadMessage.text}
                </div>
            )}
        </div>
    );
};

const ManifestPage = () => {
    const [manifests, setManifests] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [selectedManifest, setSelectedManifest] = useState(null);

    useEffect(() => {
        loadManifests();
    }, []);

    const loadManifests = async () => {
        try {
            setLoading(true);
            setError(null);
            const data = await getManifests();
            setManifests(Array.isArray(data) ? data : []);
        } catch (err) {
            console.error("Error loading manifests:", err);
            setError("Failed to load manifests");
        } finally {
            setLoading(false);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center min-h-screen">
                <div className="text-center">
                    <Clock className="h-12 w-12 text-blue-500 animate-spin mx-auto mb-3" />
                    <p className="text-slate-600">Loading manifests...</p>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="flex items-center justify-center min-h-screen">
                <div className="text-center text-red-600">
                    <X className="h-12 w-12 mx-auto mb-3" />
                    <p>{error}</p>
                    <button
                        onClick={loadManifests}
                        className="mt-4 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
                    >
                        Retry
                    </button>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen bg-slate-50">
            <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
                <div className="mb-8">
                    <h1 className="text-3xl font-bold text-slate-800 mb-2">
                        Manifest Management
                    </h1>
                    <p className="text-slate-600">
                        View and manage manifests
                    </p>
                </div>

                <UploadZone onUploadSuccess={loadManifests} />

                {manifests.length === 0 ? (
                    <div className="bg-white rounded-lg shadow-md p-12 text-center">
                        <Server className="h-16 w-16 text-slate-300 mx-auto mb-4" />
                        <h3 className="text-xl font-semibold text-slate-700 mb-2">
                            No Manifests Found
                        </h3>
                        <p className="text-slate-500">
                            There are no manifests configured for this environment yet.
                        </p>
                    </div>
                ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                        {manifests.map((manifest, idx) => (
                            <ManifestCard
                                key={idx}
                                manifest={manifest}
                                onClick={() => setSelectedManifest(manifest)}
                            />
                        ))}
                    </div>
                )}

                {selectedManifest && (
                    <ManifestDetails
                        manifest={selectedManifest}
                        onClose={() => setSelectedManifest(null)}
                    />
                )}
            </div>
        </div>
    );
};

export default ManifestPage;
